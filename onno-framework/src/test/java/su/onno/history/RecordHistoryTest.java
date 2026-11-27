package su.onno.history;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Enumeration;
import su.onno.annotations.TabularSection;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.HierarchicalCatalogObject;
import su.onno.model.TabularSectionRow;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage-level recorder behind {@code history = true} (#422): capture → write → record on one
 * handle yields created/updated/deleted/restored entries with canonical old and new values, secrets
 * without values, and nothing at all for an entity that has not opted in. Runs on H2;
 * {@link RecordHistoryPostgresIT} repeats it on PostgreSQL.
 */
class RecordHistoryTest {

    @Enumeration(name = "CoreHistoryStatuses")
    public enum Status { OPEN, CLOSED }

    @Catalog(name = "CoreHistoryCards", history = true)
    @SuppressWarnings({"unused", "FieldMayBeFinal", "MismatchedQueryAndUpdateOfCollection"})
    public static class Card extends CatalogObject {
        @Attribute(length = 60) private String title;
        @Attribute(precision = 12, scale = 3) private BigDecimal weight;
        @Attribute private LocalDate bornOn;
        @Attribute private LocalDateTime seenAt;
        @Attribute private boolean neutered;
        @Attribute private Status status;
        @Attribute(secret = true) private String pin;
        @TabularSection(name = "notes") private List<NoteLine> notes = new ArrayList<>();
    }

    @SuppressWarnings("unused")
    public static class NoteLine extends TabularSectionRow {
        @Attribute(length = 60) private String text;
    }

    @Catalog(name = "CoreHistoryPlain")
    @SuppressWarnings("unused")
    public static class Plain extends CatalogObject {
        @Attribute(length = 60) private String title;
    }

    @Catalog(name = "CoreHistoryTree", history = true, hierarchical = true)
    public static class Tree extends HierarchicalCatalogObject<Tree> { }

    protected Jdbi jdbi;
    private RecordHistory history;
    private SecretCipher cipher;
    private CatalogDescriptor card;
    private CatalogDescriptor plain;
    private EnumerationDescriptor statuses;

    protected Jdbi createJdbi() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:core_history" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }

    @BeforeEach
    void setUp() {
        jdbi = createJdbi();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        statuses = scanner.scanEnumeration(Status.class);
        registry.registerEnumeration(statuses);
        card = scanner.scan(Card.class);
        plain = scanner.scan(Plain.class);
        registry.registerCatalog(card);
        registry.registerCatalog(plain);
        registry.registerCatalog(scanner.scan(Tree.class));
        new SchemaGenerator(registry).execute(jdbi);
        cipher = new SecretCipher("core-history-key");
        history = new RecordHistory(jdbi, registry, cipher, () -> HistoryActor.named("resolved@test"));
    }

    @Test
    void hierarchyHistoryStillRecordsFolderAndParentUuids() {
        UUID id = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        jdbi.useTransaction(h -> {
            h.createUpdate("INSERT INTO catalog_core_history_tree (_id, _code, _is_folder, _parent)"
                            + " VALUES (:id, 'T', true, :parent)")
                    .bind("id", id).bind("parent", parent).execute();
            history.record(h, Tree.class, id, RecordImage.ABSENT, HistoryActor.SYSTEM);
        });
        assertThat(history.list(id).getFirst().changes()).contains(
                header("folder", null, "true"), header("parent", null, parent.toString()));
    }

    @Test
    void legacyFlatHierarchyColumnsAreNotExposedAsHistoryFields() {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> {
            h.execute("ALTER TABLE " + card.tableName() + " ADD COLUMN _is_folder BOOLEAN DEFAULT TRUE");
            h.execute("ALTER TABLE " + card.tableName() + " ADD COLUMN _parent UUID");
        });
        write(id, HistoryActor.SYSTEM, h -> h.createUpdate("INSERT INTO " + card.tableName()
                        + " (_id, _code, _description, _parent) VALUES (:id, 'L', 'Legacy', :parent)")
                .bind("id", id).bind("parent", UUID.randomUUID()).execute());
        assertThat(history.list(id).getFirst().changes()).extracting(HistoryChange::field)
                .doesNotContain("folder", "parent");
    }

    @Test
    void recordsCreateUpdateDeleteAndRestoreWithCanonicalValues() {
        UUID id = UUID.randomUUID();
        UUID open = statusId("OPEN");
        UUID closed = statusId("CLOSED");
        HistoryActor ann = new HistoryActor("ann@test", UUID.randomUUID());

        write(id, ann, h -> {
            h.createUpdate("INSERT INTO " + card.tableName()
                            + " (_id, _code, _description, _deletion_mark, _version, title, weight,"
                            + " born_on, seen_at, neutered, status, pin)"
                            + " VALUES (:id, 'C-1', 'Rex', FALSE, 0, 'Rex', 12.500, :born, :seen, FALSE, :status, :pin)")
                    .bind("id", id).bind("born", LocalDate.of(2020, 5, 17))
                    .bind("seen", LocalDateTime.of(2026, 11, 13, 9, 30))
                    .bind("status", open).bind("pin", cipher.encrypt("1234"))
                    .execute();
            insertNote(h, id, 1, "first");
        });
        write(id, null, h -> {
            h.createUpdate("UPDATE " + card.tableName()
                            + " SET title = 'Rexy', weight = 13.25, neutered = TRUE, status = :status, pin = :pin"
                            + " WHERE _id = :id")
                    .bind("id", id).bind("status", closed).bind("pin", cipher.encrypt("9999")).execute();
            h.createUpdate("UPDATE " + notesTable() + " SET text = 'edited' WHERE _parent_id = :id")
                    .bind("id", id).execute();
            insertNote(h, id, 2, "second");
        });
        // Same values again (the secret re-encrypted to new ciphertext, the decimal with other scale).
        write(id, ann, h -> h.createUpdate("UPDATE " + card.tableName()
                        + " SET weight = 13.250, pin = :pin WHERE _id = :id")
                .bind("id", id).bind("pin", cipher.encrypt("9999")).execute());
        write(id, ann, h -> h.createUpdate("UPDATE " + card.tableName()
                + " SET _deletion_mark = TRUE WHERE _id = :id").bind("id", id).execute());
        write(id, ann, h -> {
            h.createUpdate("UPDATE " + card.tableName() + " SET _deletion_mark = FALSE WHERE _id = :id")
                    .bind("id", id).execute();
            h.createUpdate("DELETE FROM " + notesTable() + " WHERE _parent_id = :id AND _line_number = 2")
                    .bind("id", id).execute();
        });
        jdbi.useHandle(h -> history.recordEvent(h, Card.class, id, RecordHistory.POSTED, null));

        List<HistoryEntry> entries = history.list(id);
        assertThat(entries).extracting(HistoryEntry::event).containsExactly(
                RecordHistory.POSTED, RecordHistory.RESTORED, RecordHistory.DELETED,
                RecordHistory.UPDATED, RecordHistory.CREATED);
        assertThat(entries).extracting(HistoryEntry::actor).containsExactly(
                "resolved@test", "ann@test", "ann@test", "resolved@test", "ann@test");
        assertThat(entries.get(4).actorId()).isEqualTo(ann.recordId());
        assertThat(entries.get(4).entityType()).isEqualTo(RecordHistory.CATALOG);
        assertThat(entries.get(4).entityName()).isEqualTo("CoreHistoryCards");

        assertThat(entries.get(4).changes()).contains(
                header("code", null, "C-1"), header("description", null, "Rex"),
                header("title", null, "Rex"),
                header("weight", null, "12.5"), header("bornOn", null, "2020-05-17"),
                header("seenAt", null, "2026-11-13T09:30"), header("neutered", null, "false"),
                header("status", null, open.toString()),
                new HistoryChange(null, null, null, "pin", null, null, true),
                new HistoryChange("notes", 1, HistoryChange.ROW_ADDED, "text", null, "first", false));
        assertThat(entries.get(4).changes()).extracting(HistoryChange::field).doesNotContain("folder", "parent");
        assertThat(entries.get(3).changes()).containsExactlyInAnyOrder(
                header("title", "Rex", "Rexy"), header("weight", "12.5", "13.25"),
                header("neutered", "false", "true"),
                header("status", open.toString(), closed.toString()),
                new HistoryChange(null, null, null, "pin", null, null, true),
                new HistoryChange("notes", 1, HistoryChange.ROW_CHANGED, "text", "first", "edited", false),
                new HistoryChange("notes", 2, HistoryChange.ROW_ADDED, "text", null, "second", false));
        assertThat(entries.get(2).changes()).isEmpty();
        assertThat(entries.get(1).changes()).containsExactly(
                new HistoryChange("notes", 2, HistoryChange.ROW_REMOVED, "text", "second", null, false));
        assertThat(entries.get(0).changes()).isEmpty();

        // Keyset paging: windows continue from the cursor without overlap, bound to their context.
        var first = history.page(id, null, 2, "ann");
        assertThat(first.rows()).extracting(HistoryEntry::event)
                .containsExactly(RecordHistory.POSTED, RecordHistory.RESTORED);
        assertThat(first.hasMore()).isTrue();
        var second = history.page(id, first.nextCursor(), 2, "ann");
        assertThat(second.rows()).extracting(HistoryEntry::event)
                .containsExactly(RecordHistory.DELETED, RecordHistory.UPDATED);
        assertThatThrownBy(() -> history.page(id, first.nextCursor(), 2, "bob"))
                .isInstanceOf(su.onno.query.Cursor.ScopeMismatchException.class);

        // No plaintext or ciphertext of the secret ever reaches the table.
        List<String> values = jdbi.withHandle(h -> h.createQuery("SELECT COALESCE(_old_value, '') || COALESCE(_new_value, '')"
                + " FROM " + RecordHistory.TABLE + " WHERE _field = 'pin'").mapTo(String.class).list());
        assertThat(values).isNotEmpty().allMatch(String::isEmpty);
    }

    @Test
    void historyRollsBackWithTheWriteTransaction() {
        UUID id = UUID.randomUUID();
        try {
            jdbi.useTransaction(h -> {
                h.createUpdate("INSERT INTO " + card.tableName()
                                + " (_id, _code, _description, _deletion_mark, _version, neutered)"
                                + " VALUES (:id, 'C-2', 'Tom', FALSE, 0, FALSE)")
                        .bind("id", id).execute();
                history.record(h, Card.class, id, RecordImage.ABSENT, HistoryActor.SYSTEM);
                throw new IllegalStateException("write rejected");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(history.list(id)).isEmpty();
    }

    @Test
    void untrackedEntitiesAndTheDisabledRecorderCostNothing() {
        UUID id = UUID.randomUUID();
        assertThat(history.tracks(Card.class)).isTrue();
        assertThat(history.tracks(Plain.class)).isFalse();
        jdbi.useTransaction(h -> {
            assertThat(history.capture(h, Plain.class, id)).isNull();
            h.createUpdate("INSERT INTO " + plain.tableName()
                            + " (_id, _code, _description, _deletion_mark, _version, title)"
                            + " VALUES (:id, 'P-1', 'x', FALSE, 0, 'x')").bind("id", id).execute();
            history.record(h, Plain.class, id, RecordImage.ABSENT, null);
        });
        jdbi.useHandle(h -> history.recordEvent(h, Plain.class, id, RecordHistory.POSTED, null));
        assertThat(history.list(id)).isEmpty();

        RecordHistory off = RecordHistory.disabled();
        assertThat(off.tracks(Card.class)).isFalse();
        assertThat(off.list(id)).isEmpty();
        off.recordEvent(null, Card.class, id, RecordHistory.POSTED, null);
    }

    @Test
    void canonicalTextIsStableAcrossDriverTypes() {
        assertThat(RecordHistory.text(new BigDecimal("12.500"))).isEqualTo("12.5");
        assertThat(RecordHistory.text(new BigDecimal("0.000"))).isEqualTo("0");
        assertThat(RecordHistory.text(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(RecordHistory.text(Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 3, 4))))
                .isEqualTo("2026-01-02T03:04");
        assertThat(RecordHistory.text(java.sql.Date.valueOf(LocalDate.of(2026, 1, 2)))).isEqualTo("2026-01-02");
        assertThat(RecordHistory.text(null)).isNull();
    }

    /** One write the way every path does it: capture, mutate, record — on one transactional handle. */
    private void write(UUID id, HistoryActor actor, Consumer<Handle> mutation) {
        jdbi.useTransaction(h -> {
            RecordImage before = history.capture(h, Card.class, id);
            mutation.accept(h);
            history.record(h, Card.class, id, before, actor);
        });
    }

    private void insertNote(Handle h, UUID parent, int line, String text) {
        h.createUpdate("INSERT INTO " + notesTable() + " (_id, _parent_id, _line_number, text)"
                        + " VALUES (:id, :parent, :line, :text)")
                .bind("id", UUID.randomUUID()).bind("parent", parent).bind("line", line).bind("text", text)
                .execute();
    }

    private String notesTable() {
        return card.tabularSections().getFirst().tableName();
    }

    private UUID statusId(String name) {
        return statuses.values().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow().id();
    }

    private static HistoryChange header(String field, String oldValue, String newValue) {
        return new HistoryChange(null, null, null, field, oldValue, newValue, false);
    }
}
