package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.Enumeration;
import su.onno.annotations.TabularSection;
import su.onno.history.HistoryActor;
import su.onno.history.HistoryActors;
import su.onno.history.RecordHistory;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.types.Ref;
import su.onno.ui.history.RecordHistoryController;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Record change history (#422) on the generic command path and its read endpoint: opted-in
 * catalogs/documents record who changed which field from what to what (tabular rows included),
 * secrets never reach the table, and {@code /api/history} applies the record's own read access —
 * entity grant, record policy, reference visibility.
 */
class RecordHistoryCommandPathTest {

    @Enumeration(name = "HistSpecies")
    public enum Species { DOG, CAT }

    @Catalog(name = "HistOwners")
    @AccessControl(readRoles = {"VET", "CLIENT"}, writeRoles = "VET")
    @SuppressWarnings("unused")
    public static class Owner extends CatalogObject {
        @Attribute(length = 20) private String tier;
    }

    @Catalog(name = "HistPatients", history = true)
    @AccessControl(readRoles = {"VET", "CLIENT"}, writeRoles = "VET")
    @SuppressWarnings({"unused", "FieldMayBeFinal", "MismatchedQueryAndUpdateOfCollection"})
    public static class Patient extends CatalogObject {
        @Attribute(length = 60, displayName = "Pet name") private String name;
        @Attribute private Species species;
        @Attribute private Ref<Owner> owner;
        @Attribute(secret = true) private String chipPin;
        @Attribute(length = 20) private String tier;
        @TabularSection(name = "allergies") private List<AllergyLine> allergies = new ArrayList<>();
    }

    @SuppressWarnings("unused")
    public static class AllergyLine extends TabularSectionRow {
        @Attribute(length = 60) private String substance;
    }

    @Document(name = "HistVisits", numberPrefix = "V-", history = true)
    @AccessControl(readRoles = "VET", writeRoles = "VET")
    @SuppressWarnings({"unused", "FieldMayBeFinal", "MismatchedQueryAndUpdateOfCollection"})
    public static class Visit extends DocumentObject {
        @Attribute(length = 200) private String complaint;
        @Attribute(precision = 10, scale = 2) private BigDecimal weight;
        @TabularSection(name = "procedures") private List<ProcedureLine> procedures = new ArrayList<>();
    }

    @SuppressWarnings("unused")
    public static class ProcedureLine extends TabularSectionRow {
        @Attribute(length = 60) private String title;
        @Attribute(precision = 10, scale = 2) private BigDecimal price;
    }

    @Catalog(name = "HistUntracked")
    @AccessControl(readRoles = "VET", writeRoles = "VET")
    @SuppressWarnings("unused")
    public static class Untracked extends CatalogObject {
        @Attribute(length = 60) private String label;
    }

    private static final AccessSubject VET = AccessSubject.user("vet@clinic.test", Set.of("VET"));
    private static final AccessSubject OTHER_VET = AccessSubject.user("second@clinic.test", Set.of("VET"));
    private static final AccessSubject CLIENT = AccessSubject.user("client@clinic.test", Set.of("CLIENT"));
    private static final AccessSubject OUTSIDER = AccessSubject.user("x@clinic.test", Set.of("NOBODY"));

    private Jdbi jdbi;
    private RecordHistory history;
    private CatalogCommandService catalogs;
    private DocumentCommandService documents;
    private RecordHistoryController controller;
    private CatalogDescriptor ownerDesc;
    private CatalogDescriptor patientDesc;
    private CatalogDescriptor untrackedDesc;
    private DocumentDescriptor visitDesc;
    private EnumerationDescriptor species;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:history" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        species = scanner.scanEnumeration(Species.class);
        registry.registerEnumeration(species);
        ownerDesc = scanner.scan(Owner.class);
        patientDesc = scanner.scan(Patient.class);
        untrackedDesc = scanner.scan(Untracked.class);
        visitDesc = scanner.scanDocument(Visit.class);
        registry.registerCatalog(ownerDesc);
        registry.registerCatalog(patientDesc);
        registry.registerCatalog(untrackedDesc);
        registry.registerDocument(visitDesc);
        new SchemaGenerator(registry).execute(jdbi);

        // CLIENT sees only "own" patients and "own" owners; VET is unscoped.
        RecordScopeCompiler scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Patient.class).appliesTo("CLIENT")
                        .read(RecordScope.eq("tier", "own")),
                RecordAccessPolicy.forCatalog(Owner.class).appliesTo("CLIENT")
                        .read(RecordScope.eq("tier", "own")))));
        UiAccessService access = new UiAccessService(registry);
        SecretCipher cipher = new SecretCipher("history-test-key");
        history = new RecordHistory(jdbi, registry, cipher, null);

        CatalogQueryService catalogQuery = new CatalogQueryService(registry, jdbi, scopes, access);
        DocumentQueryService documentQuery = new DocumentQueryService(registry, jdbi, scopes, access);
        UiProperties props = new UiProperties();
        NumberGenerator numbers = new Numbers();
        catalogs = new CatalogCommandService(registry, jdbi, props, numbers, catalogQuery, access,
                event -> { }, cipher);
        catalogs.setHistory(history);
        documents = new DocumentCommandService(registry, jdbi, props, numbers, null, documentQuery, access,
                event -> { }, cipher);
        documents.setHistory(history);
        controller = new RecordHistoryController(history, access, catalogQuery, documentQuery,
                new FieldHintResolver(List.of()), UiMessages.defaults());
    }

    @Test
    void catalogWritesRecordFieldLevelDiffsAttributedToTheCaller() {
        UUID owner = createOwner("Ann", "own");
        UUID otherOwner = createOwner("Bob", "own");
        UUID id = id(catalogs.create(patientDesc, Map.of(
                "description", "Rex", "name", "Rex", "species", enumId("DOG"), "owner", owner.toString(),
                "allergies", List.of(Map.of("substance", "penicillin"))), VET));

        catalogs.update(patientDesc, id, Map.of(
                "name", "Rexy", "species", enumId("CAT"), "owner", otherOwner.toString(),
                "allergies", List.of(Map.of("substance", "latex"), Map.of("substance", "pollen"))), OTHER_VET);
        catalogs.delete(patientDesc, id, VET);

        List<Map<String, Object>> entries = entries("catalogs", "HistPatients", id, VET);
        assertThat(entries).extracting(e -> e.get("event")).containsExactly("DELETED", "UPDATED", "CREATED");
        assertThat(entries).extracting(e -> e.get("actor"))
                .containsExactly("vet@clinic.test", "second@clinic.test", "vet@clinic.test");

        Map<String, Map<String, Object>> updated = headerChanges(entries.get(1));
        assertThat(updated.keySet()).containsExactlyInAnyOrder("name", "species", "owner");
        assertThat(updated.get("name")).containsEntry("old", "Rex").containsEntry("new", "Rexy")
                .containsEntry("label", "Pet name").containsEntry("type", "text");
        assertThat(updated.get("species")).containsEntry("oldDisplay", "DOG").containsEntry("newDisplay", "CAT")
                .containsEntry("type", "enum");
        assertThat(updated.get("owner")).containsEntry("old", owner.toString())
                .containsEntry("oldDisplay", "Ann").containsEntry("newDisplay", "Bob").containsEntry("type", "ref");

        List<Map<String, Object>> lines = sectionChanges(entries.get(1));
        assertThat(lines).anySatisfy(c -> assertThat(c)
                .containsEntry("section", "allergies").containsEntry("line", 1).containsEntry("rowChange", "CHANGED")
                .containsEntry("field", "substance").containsEntry("old", "penicillin").containsEntry("new", "latex"));
        assertThat(lines).anySatisfy(c -> assertThat(c)
                .containsEntry("line", 2).containsEntry("rowChange", "ADDED").containsEntry("new", "pollen"));

        // The create entry carries the initial values (old side empty), lines included.
        assertThat(headerChanges(entries.get(2)).get("name")).containsEntry("old", null).containsEntry("new", "Rex");
        assertThat(sectionChanges(entries.get(2))).singleElement().satisfies(c -> assertThat(c)
                .containsEntry("rowChange", "ADDED").containsEntry("new", "penicillin"));
    }

    @Test
    void secretValuesAreRecordedAsChangedButNeverStored() {
        UUID id = id(catalogs.create(patientDesc, Map.of("description", "Rex", "chipPin", "1234-secret"), VET));
        catalogs.update(patientDesc, id, Map.of("chipPin", "5678-secret"), VET);
        // Re-sending the same secret re-encrypts to different ciphertext; that is not a change.
        catalogs.update(patientDesc, id, Map.of("chipPin", "5678-secret"), VET);

        List<Map<String, Object>> entries = entries("catalogs", "HistPatients", id, VET);
        assertThat(entries).extracting(e -> e.get("event")).containsExactly("UPDATED", "CREATED");
        assertThat(headerChanges(entries.get(0)).get("chipPin"))
                .containsEntry("secret", true).containsEntry("old", null).containsEntry("new", null);

        List<String> stored = jdbi.withHandle(h -> h.createQuery(
                        "SELECT COALESCE(_old_value, '') || '|' || COALESCE(_new_value, '') FROM " + RecordHistory.TABLE)
                .mapTo(String.class).list());
        assertThat(stored).noneMatch(v -> v.contains("secret"));
    }

    @Test
    void aWriteThatChangesNothingAndUntrackedEntitiesRecordNothing() {
        UUID id = id(catalogs.create(patientDesc, Map.of("description", "Rex", "name", "Rex"), VET));
        catalogs.update(patientDesc, id, Map.of("name", "Rex"), VET);
        assertThat(entries("catalogs", "HistPatients", id, VET)).hasSize(1);

        UUID plain = id(catalogs.create(untrackedDesc, Map.of("description", "x", "label", "a"), VET));
        catalogs.update(untrackedDesc, plain, Map.of("label", "b"), VET);
        assertThat(history.list(plain, null, 10)).isEmpty();
        assertThat(history.tracks(Untracked.class)).isFalse();
        assertThatThrownBy(() -> controller.list("catalogs", "HistUntracked", plain, null, null, VET))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void documentHeaderAndLinesAreRecordedTogether() {
        UUID id = id(documents.create(visitDesc, Map.of(
                "complaint", "limping", "weight", "12.50",
                "procedures", List.of(Map.of("title", "X-ray", "price", "40.00"))), VET));
        documents.update(visitDesc, id, Map.of(
                "weight", "12.5", "complaint", "limping badly",
                "procedures", List.of()), VET);
        documents.delete(visitDesc, id, VET);

        List<Map<String, Object>> entries = entries("documents", "HistVisits", id, VET);
        assertThat(entries).extracting(e -> e.get("event")).containsExactly("DELETED", "UPDATED", "CREATED");
        // 12.50 → 12.5 is the same number: only the complaint changed.
        assertThat(headerChanges(entries.get(1)).keySet()).containsExactly("complaint");
        assertThat(sectionChanges(entries.get(1))).allSatisfy(c -> assertThat(c)
                .containsEntry("section", "procedures").containsEntry("rowChange", "REMOVED"));
        assertThat(headerChanges(entries.get(2))).containsKeys("number", "date", "complaint", "weight");
    }

    @Test
    void aFailedDocumentWriteLeavesNoHistoryAndNoHalfWrittenLines() {
        UUID id = id(documents.create(visitDesc, Map.of(
                "complaint", "limping", "procedures", List.of(Map.of("title", "X-ray"))), VET));
        String tooLong = "x".repeat(500);
        assertThatThrownBy(() -> documents.update(visitDesc, id, Map.of(
                "complaint", "changed", "procedures", List.of(Map.of("title", tooLong))), VET))
                .isInstanceOf(RuntimeException.class);

        // The line insert failed inside the write transaction: header, lines and history rolled back.
        assertThat(entries("documents", "HistVisits", id, VET)).hasSize(1);
        String complaint = jdbi.withHandle(h -> h.createQuery("SELECT complaint FROM " + visitDesc.tableName()
                + " WHERE _id = :id").bind("id", id).mapTo(String.class).one());
        assertThat(complaint).isEqualTo("limping");
        Integer lines = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM "
                        + visitDesc.tabularSections().getFirst().tableName() + " WHERE _parent_id = :id")
                .bind("id", id).mapTo(Integer.class).one());
        assertThat(lines).isEqualTo(1);
    }

    @Test
    void readingHistoryNeedsTheSameAccessAsReadingTheRecord() {
        UUID visible = createOwner("Ann", "own");
        UUID hidden = createOwner("Zed", "staff");
        UUID own = id(catalogs.create(patientDesc, Map.of(
                "description", "Rex", "tier", "own", "owner", hidden.toString()), VET));
        catalogs.update(patientDesc, own, Map.of("owner", visible.toString()), VET);
        UUID foreign = id(catalogs.create(patientDesc, Map.of("description", "Tom", "tier", "staff"), VET));

        // No entity read grant → 403; outside the record policy → 404, like a missing record.
        assertThatThrownBy(() -> controller.list("catalogs", "HistPatients", own, null, null, OUTSIDER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.list("catalogs", "HistPatients", foreign, null, null, CLIENT))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // Inside the scope the client reads the history, but a reference it may not read is masked.
        Map<String, Object> ownerChange = headerChanges(entries("catalogs", "HistPatients", own, CLIENT).get(0))
                .get("owner");
        assertThat(ownerChange).containsEntry("old", null).containsEntry("oldDisplay", RefResolver.RESTRICTED_DISPLAY)
                .containsEntry("new", visible.toString()).containsEntry("newDisplay", "Ann");
        // The unscoped vet sees both sides.
        assertThat(headerChanges(entries("catalogs", "HistPatients", own, VET).get(0)).get("owner"))
                .containsEntry("old", hidden.toString()).containsEntry("oldDisplay", "Zed");
    }

    @Test
    void historyPagesNewestFirstByKeyset() {
        UUID id = id(catalogs.create(patientDesc, Map.of("description", "Rex", "name", "a"), VET));
        catalogs.update(patientDesc, id, Map.of("name", "b"), VET);
        catalogs.update(patientDesc, id, Map.of("name", "c"), VET);

        Map<String, Object> first = controller.list("catalogs", "HistPatients", id, 2, null, VET);
        assertThat(list(first)).extracting(e -> e.get("event")).containsExactly("UPDATED", "UPDATED");
        assertThat(first.get("nextBefore")).isNotNull();
        Map<String, Object> second = controller.list("catalogs", "HistPatients", id, 2,
                first.get("nextBefore").toString(), VET);
        assertThat(list(second)).extracting(e -> e.get("event")).containsExactly("CREATED");
        assertThat(second.get("nextBefore")).isNull();
    }

    @Test
    void trustedCodeCanBindItsOwnActor() {
        UUID id = HistoryActors.callAs(HistoryActor.named("import"), () ->
                id(catalogs.create(patientDesc, Map.of("description", "Rex"), AccessSubject.system())));
        // The command binds its own subject, so a system write is recorded as the system actor.
        assertThat(entries("catalogs", "HistPatients", id, VET).get(0)).containsEntry("actor", "system");
        assertThat(HistoryActors.current()).isNull();
    }

    private UUID createOwner(String description, String tier) {
        return id(catalogs.create(ownerDesc, Map.of("description", description, "tier", tier), VET));
    }

    private String enumId(String constant) {
        return species.values().stream().filter(v -> v.name().equals(constant)).findFirst().orElseThrow()
                .id().toString();
    }

    private List<Map<String, Object>> entries(String kind, String name, UUID id, AccessSubject subject) {
        return list(controller.list(kind, name, id, null, null, subject));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("entries");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> changes(Map<String, Object> entry) {
        return (List<Map<String, Object>>) entry.get("changes");
    }

    private static Map<String, Map<String, Object>> headerChanges(Map<String, Object> entry) {
        Map<String, Map<String, Object>> byField = new java.util.LinkedHashMap<>();
        for (Map<String, Object> change : changes(entry)) {
            if (change.get("section") == null) byField.put((String) change.get("field"), change);
        }
        return byField;
    }

    private static List<Map<String, Object>> sectionChanges(Map<String, Object> entry) {
        return changes(entry).stream().filter(c -> c.get("section") != null).toList();
    }

    private static UUID id(Map<String, Object> row) {
        return UUID.fromString(row.get("_id").toString());
    }

    private static final class Numbers implements NumberGenerator {
        private final AtomicInteger next = new AtomicInteger();

        @Override
        public String nextNumber(String entityName, int length) {
            return "N" + next.incrementAndGet();
        }

        @Override
        public String nextCode(String entityName, int length) {
            return "C" + next.incrementAndGet();
        }
    }
}
