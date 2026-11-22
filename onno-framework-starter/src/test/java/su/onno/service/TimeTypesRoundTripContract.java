package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code LocalTime} and {@code Instant} attributes (#548) through the generic write/read path:
 * catalogs, documents, tabular rows, lifecycle materialization, range filters and keyset sorting.
 * Run against H2 and PostgreSQL by the two subclasses — the drivers surface {@code TIME} and
 * {@code TIMESTAMP WITH TIME ZONE} as different Java types, and only PostgreSQL refuses to compare
 * a temporal column with a varchar parameter.
 */
abstract class TimeTypesRoundTripContract {

    @Catalog(name = "Tt Shifts", autoNumber = false)
    static class TtShift extends CatalogObject implements BeforeWriteHandler {
        static LocalTime seenStart;
        static Instant seenOpened;

        @Attribute private LocalTime startsAt;
        @Attribute private Instant openedAt;

        @Override
        public void beforeWrite() {
            seenStart = startsAt;
            seenOpened = openedAt;
        }
    }

    @Document(name = "Tt Visits", numberPrefix = "TV-")
    static class TtVisit extends DocumentObject {
        @Attribute private Instant checkedInAt;
        @Attribute private LocalTime slot;

        @TabularSection(name = "steps")
        private List<TtVisitStep> steps = new ArrayList<>();
    }

    static class TtVisitStep extends TabularSectionRow {
        @Attribute private LocalTime at;
        @Attribute private Instant doneAt;
    }

    private final AccessSubject admin = AccessSubject.user("admin", java.util.Set.of("ADMIN"));
    private Jdbi jdbi;
    private CatalogDescriptor shiftDesc;
    private DocumentDescriptor visitDesc;
    private CatalogQueryService catalogQuery;
    private DocumentQueryService documentQuery;
    private CatalogCommandService catalogCommands;
    private DocumentCommandService documentCommands;

    /** A connection to an empty-or-reusable database of the dialect under test. */
    abstract javax.sql.DataSource dataSource();

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(dataSource());

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        shiftDesc = scanner.scan(TtShift.class);
        registry.registerCatalog(shiftDesc);
        visitDesc = scanner.scanDocument(TtVisit.class);
        registry.registerDocument(visitDesc);

        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS " + visitDesc.tabularSections().get(0).tableName());
            h.execute("DROP TABLE IF EXISTS " + visitDesc.tableName());
            h.execute("DROP TABLE IF EXISTS " + shiftDesc.tableName());
        });
        new SchemaGenerator(registry).execute(jdbi);

        WebProperties props = new WebProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        AccessService access = new AccessService(registry);
        ApplicationEventPublisher events = event -> { };
        catalogQuery = new CatalogQueryService(registry, jdbi);
        documentQuery = new DocumentQueryService(registry, jdbi);
        TestEntityStore.Stack stack = TestEntityStore.builder(dataSource(), registry).numbers(numbers).cipher(cipher).events(events::publishEvent).build();
        catalogCommands = new CatalogCommandService(registry, stack.jdbi(), props, catalogQuery, access, stack.store());
        documentCommands = new DocumentCommandService(registry, stack.jdbi(), props, null, documentQuery, access, stack.store());
    }

    @Test
    void catalog_roundTripsTimeAndInstant_andReadPayloadWritesBackUnchanged() {
        UUID id = shift("s-1", "09:30", "2026-01-16T04:00:00+03:00");

        // The lifecycle hook saw typed values, with the offset folded into the absolute moment.
        assertThat(TtShift.seenStart).isEqualTo(LocalTime.of(9, 30));
        assertThat(TtShift.seenOpened).isEqualTo(Instant.parse("2026-01-16T01:00:00Z"));

        Map<String, Object> read = catalogQuery.get(AccessSubject.system(), shiftDesc, id);
        assertThat(read.get("starts_at")).isEqualTo("09:30");
        // An instant always reads back as ISO-8601 UTC, whatever offset it was written with.
        assertThat(read.get("opened_at")).isEqualTo("2026-01-16T01:00:00Z");

        catalogCommands.update(shiftDesc, id, Map.of(
                "startsAt", read.get("starts_at"), "openedAt", read.get("opened_at")), admin);
        assertThat(rawInstant(shiftDesc.tableName(), "opened_at", id))
                .isEqualTo(Instant.parse("2026-01-16T01:00:00Z"));
        assertThat(rawTime(shiftDesc.tableName(), "starts_at", id)).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void secondsSurvive_andNullsStayNull() {
        UUID id = shift("s-2", "23:59:58", "2026-06-30T21:15:42Z");
        Map<String, Object> read = catalogQuery.get(AccessSubject.system(), shiftDesc, id);
        assertThat(read.get("starts_at")).isEqualTo("23:59:58");
        assertThat(read.get("opened_at")).isEqualTo("2026-06-30T21:15:42Z");

        UUID blank = (UUID) catalogCommands.create(shiftDesc, Map.of("code", "s-3"), admin).get("_id");
        Map<String, Object> empty = catalogQuery.get(AccessSubject.system(), shiftDesc, blank);
        assertThat(empty.get("starts_at")).isNull();
        assertThat(empty.get("opened_at")).isNull();
    }

    @Test
    void offsetFreeInstant_andMalformedTime_areRejected() {
        // An Instant is an absolute moment: a wall-clock string would have to be guessed against
        // some zone, so it is refused rather than silently shifted.
        assertThatThrownBy(() -> shift("bad-1", "09:00", "2026-01-16T04:00:00"))
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> shift("bad-2", "9 o'clock", "2026-01-16T04:00:00Z"))
                .hasMessageContaining("HH:mm");
    }

    @Test
    void documentAndTabularRows_roundTrip() {
        UUID id = (UUID) documentCommands.create(visitDesc, Map.of(
                "checkedInAt", "2026-03-01T10:00:00Z",
                "slot", "10:15",
                "steps", List.of(Map.of("at", "10:20", "doneAt", "2026-03-01T12:20:00+02:00"))
        ), admin).get("_id");

        Map<String, Object> read = documentQuery.get(AccessSubject.system(), visitDesc, id);
        assertThat(read.get("checked_in_at")).isEqualTo("2026-03-01T10:00:00Z");
        assertThat(read.get("slot")).isEqualTo("10:15");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.get("steps");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("at")).isEqualTo("10:20");
            assertThat(r.get("done_at")).isEqualTo("2026-03-01T10:20:00Z");
        });

        documentCommands.update(visitDesc, id, Map.of(
                "checkedInAt", read.get("checked_in_at"),
                "slot", read.get("slot"),
                "steps", List.of(Map.of("at", rows.get(0).get("at"), "doneAt", rows.get(0).get("done_at")))
        ), admin);
        assertThat(rawInstant(visitDesc.tableName(), "checked_in_at", id))
                .isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
    }

    @Test
    void rangeFilters_compareNatively() {
        shift("early", "07:00", "2026-01-10T00:00:00Z");
        shift("mid", "09:30", "2026-01-16T01:00:00Z");
        shift("late", "18:00", "2026-02-01T00:00:00Z");

        assertThat(codes(null, List.of("starts_at,08:00"), List.of("starts_at,12:00")))
                .containsExactly("mid");
        // The bound carries its own offset: 04:00+03:00 is the same moment as 01:00Z, inclusive.
        assertThat(codes(null, List.of("opened_at,2026-01-16T04:00:00+03:00"), null))
                .containsExactlyInAnyOrder("mid", "late");
        assertThat(codes(null, null, List.of("opened_at,2026-01-16T00:59:59Z")))
                .containsExactly("early");
    }

    @Test
    void keysetPaging_sortsByTimeAndInstant() {
        shift("b", "09:30", "2026-01-16T01:00:00Z");
        shift("a", "07:00", "2026-02-01T00:00:00Z");
        shift("c", "18:00", "2026-01-10T00:00:00Z");

        assertThat(pagedCodes("starts_at")).containsExactly("a", "b", "c");
        assertThat(pagedCodes("opened_at")).containsExactly("c", "b", "a");
    }

    /** Walks the list one row per window, so every step crosses a cursor on the sort column. */
    private List<String> pagedCodes(String sortColumn) {
        List<String> out = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            KeysetPage page = catalogQuery.keysetPage(admin, shiftDesc, cursor, 1, sortColumn, false,
                    null, null, null, null, null, null, null, null);
            page.rows().forEach(r -> out.add((String) r.get("_code")));
            if (!page.hasMore()) break;
            cursor = page.nextCursor();
        }
        return out;
    }

    private List<String> codes(List<String> eq, List<String> ge, List<String> le) {
        return catalogQuery.keysetPage(admin, shiftDesc, null, 50, "_code", false,
                        null, eq, null, null, null, ge, le, null)
                .rows().stream().map(r -> (String) r.get("_code")).toList();
    }

    private UUID shift(String code, String startsAt, String openedAt) {
        return (UUID) catalogCommands.create(shiftDesc, Map.of(
                "code", code, "startsAt", startsAt, "openedAt", openedAt), admin).get("_id");
    }

    private Instant rawInstant(String table, String column, UUID id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT " + column + " FROM " + table + " WHERE _id = :id")
                        .bind("id", id).mapTo(Instant.class).one());
    }

    private LocalTime rawTime(String table, String column, UUID id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT " + column + " FROM " + table + " WHERE _id = :id")
                        .bind("id", id).mapTo(LocalTime.class).one());
    }

    static final class SequentialNumbers implements NumberGenerator {
        private final AtomicInteger counter = new AtomicInteger();
        @Override public String nextNumber(String entityName, int length) {
            return String.format("%0" + Math.max(1, length) + "d", counter.incrementAndGet());
        }
        @Override public String nextCode(String entityName, int length) {
            return String.format("%0" + Math.max(1, length) + "d", counter.incrementAndGet());
        }
    }
}
