package su.onno.ui;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.*;
import su.onno.model.DocumentObject;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentReadExclusionsTest {
    @Document(name = "Reports")
    static class Report extends DocumentObject {
        @Attribute String summary;
        @Attribute String payload;
    }

    @Test void excludesAtSqlLevelForPagingDetailRefreshAndSearch() {
        var registry = new MetadataRegistry();
        var desc = new MetadataScanner(new DefaultNamingStrategy()).scanDocument(Report.class);
        registry.registerDocument(desc);
        var db = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        db.useHandle(h -> {
            // No payload column: SELECT * followed by Java removal cannot satisfy the key assertion below.
            h.execute("create table " + desc.tableName() + " (_id uuid, _number varchar, _date timestamp, _posted boolean, _deletion_mark boolean, _version int, summary varchar, unexpected_blob varchar)");
            h.execute("insert into " + desc.tableName() + " values (?, '1', timestamp '2026-09-09 12:00:00', false, false, 0, 'result', 'must not be selected')", first);
            h.execute("insert into " + desc.tableName() + " values (?, '2', timestamp '2026-09-09 11:00:00', false, false, 0, 'result', 'must not be selected')", second);
        });
        var query = new DocumentQueryService(registry, db);
        query.setReadExclusions(Map.of("Reports", List.of("payload")));
        var page = page(query, desc, null);
        assertThat(page.rows()).singleElement().satisfies(r -> assertThat(r).containsEntry("_id", first).doesNotContainKeys("payload", "unexpected_blob"));
        assertThat(page.hasMore()).isTrue();
        assertThat(page(query, desc, page.nextCursor()).rows()).singleElement().satisfies(r -> assertThat(r).containsEntry("_id", second));
        assertThat(query.get(desc, first)).containsEntry("summary", "result").doesNotContainKeys("payload", "unexpected_blob");
        assertThat(query.rowsByIds(desc, List.of(first))).singleElement().satisfies(r -> assertThat(r).doesNotContainKeys("payload", "unexpected_blob"));
        assertThat(query.search(desc, "result", 10)).hasSize(2);
        assertThat(query.sortableColumns(desc)).doesNotContain("payload");
        assertThat(query.require("Reports").attributes()).hasSize(2); // persistence metadata untouched
        assertThat(new DocumentQueryService(registry, db).get(desc, first)).containsKey("unexpected_blob"); // default unchanged
    }

    private KeysetPage page(DocumentQueryService q, DocumentDescriptor d, String cursor) {
        return q.keysetPage(d, cursor, 1, null, true, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }
}
