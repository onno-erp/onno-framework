package su.onno.web;

import org.jdbi.v3.core.Jdbi;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Durable action journal for prepared list exports. It contains neither filters nor exported data. */
final class ListExportJournal {
    private final Jdbi jdbi;

    ListExportJournal(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    void initialize() {
        jdbi.useHandle(handle -> handle.execute("""
                CREATE TABLE IF NOT EXISTS onno_list_export_journal (
                    id UUID PRIMARY KEY,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    actor VARCHAR(512) NOT NULL,
                    entity_kind VARCHAR(64) NOT NULL,
                    entity_name VARCHAR(512) NOT NULL,
                    list_view VARCHAR(64),
                    file_format VARCHAR(8) NOT NULL
                )
                """));
    }

    void prepared(String actor, String kind, String name, String view, String format) {
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO onno_list_export_journal
                    (id, occurred_at, actor, entity_kind, entity_name, list_view, file_format)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC), actor, kind, name, view, format));
    }
}
