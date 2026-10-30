package su.onno.ui;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code onno_ref_recent}: which records a user recently picked in a reference
 * field, per target entity, so the ref picker can offer them first (#409). Like
 * {@link su.onno.ui.notifications.NotificationStore}'s table it is infrastructure, created with a
 * plain {@code CREATE TABLE IF NOT EXISTS} at startup, never modelled or served by the generic API.
 *
 * <p>The store holds bare ids only. It is a hint, not an authority: every read goes back through
 * the target's query service, so a remembered record the user can no longer see (record policy,
 * deletion mark, the field's {@code refFilter}) is simply not offered. Each (owner, target) pair
 * keeps its {@link #KEEP} most recent picks; older ones are pruned on write.
 */
public class RefRecentStore {

    static final String TABLE = "onno_ref_recent";

    /** Picks remembered per (owner, target); enough headroom for a filtered-down top five. */
    static final int KEEP = 20;

    private final Jdbi jdbi;

    public RefRecentStore(Jdbi jdbi) {
        this.jdbi = jdbi;
        ensureSchema();
    }

    private void ensureSchema() {
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (\n" +
                    "    _owner VARCHAR(256) NOT NULL,\n" +
                    "    _target_kind VARCHAR(16) NOT NULL,\n" +
                    "    _target_name VARCHAR(256) NOT NULL,\n" +
                    "    _record_id UUID NOT NULL,\n" +
                    "    _used_at TIMESTAMP NOT NULL,\n" +
                    "    PRIMARY KEY (_owner, _target_kind, _target_name, _record_id)\n" +
                    ")");
            h.execute("CREATE INDEX IF NOT EXISTS onno_ref_recent_used_idx ON " + TABLE +
                    " (_owner, _target_kind, _target_name, _used_at DESC)");
        });
    }

    /**
     * Remember that {@code owner} just picked {@code recordId} for a {@code kind}/{@code name}
     * target: refresh its timestamp if already known, insert it otherwise, then prune the pair to
     * {@link #KEEP} entries.
     */
    public void record(String owner, String kind, String name, UUID recordId) {
        try {
            upsert(owner, kind, name, recordId);
        } catch (UnableToExecuteStatementException race) {
            // A concurrent pick of the same record inserted first (duplicate key). PostgreSQL aborts
            // the whole transaction on that error, so retry it: the UPDATE now finds the row.
            upsert(owner, kind, name, recordId);
        }
    }

    private void upsert(String owner, String kind, String name, UUID recordId) {
        jdbi.useTransaction(h -> {
            Timestamp now = nextUsedAt(h, owner, kind, name);
            if (touch(h, owner, kind, name, recordId, now) == 0) {
                h.createUpdate("INSERT INTO " + TABLE +
                                " (_owner, _target_kind, _target_name, _record_id, _used_at)" +
                                " VALUES (:owner, :kind, :name, :id, :usedAt)")
                        .bind("owner", owner).bind("kind", kind).bind("name", name)
                        .bind("id", recordId).bind("usedAt", now)
                        .execute();
            }
            h.createUpdate("DELETE FROM " + TABLE +
                            " WHERE _owner = :owner AND _target_kind = :kind AND _target_name = :name" +
                            " AND _record_id NOT IN (SELECT _record_id FROM " + TABLE +
                            " WHERE _owner = :owner AND _target_kind = :kind AND _target_name = :name" +
                            " ORDER BY _used_at DESC, _record_id LIMIT :keep)")
                    .bind("owner", owner).bind("kind", kind).bind("name", name).bind("keep", KEEP)
                    .execute();
        });
    }

    /** The owner's remembered picks for a target, most recent first, at most {@code limit}. */
    public List<UUID> recent(String owner, String kind, String name, int limit) {
        return jdbi.withHandle(h -> h.createQuery("SELECT _record_id FROM " + TABLE +
                        " WHERE _owner = :owner AND _target_kind = :kind AND _target_name = :name" +
                        " ORDER BY _used_at DESC, _record_id LIMIT :limit")
                .bind("owner", owner).bind("kind", kind).bind("name", name)
                .bind("limit", Math.max(1, limit))
                .mapTo(UUID.class)
                .list());
    }

    /**
     * The current time at microsecond precision (what both H2 and PostgreSQL {@code TIMESTAMP}
     * keep), nudged past the pair's newest pick when the clock has not advanced: two picks in the
     * same tick must still order as picked.
     */
    private static Timestamp nextUsedAt(Handle h, String owner, String kind, String name) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp latest = h.createQuery("SELECT MAX(_used_at) FROM " + TABLE +
                        " WHERE _owner = :owner AND _target_kind = :kind AND _target_name = :name")
                .bind("owner", owner).bind("kind", kind).bind("name", name)
                .mapTo(Timestamp.class)
                .one();
        if (latest != null && !now.isAfter(latest.toInstant())) {
            now = latest.toInstant().plus(1, ChronoUnit.MICROS);
        }
        return Timestamp.from(now);
    }

    private static int touch(Handle h, String owner, String kind, String name, UUID recordId, Timestamp now) {
        return h.createUpdate("UPDATE " + TABLE + " SET _used_at = :usedAt" +
                        " WHERE _owner = :owner AND _target_kind = :kind AND _target_name = :name" +
                        " AND _record_id = :id")
                .bind("owner", owner).bind("kind", kind).bind("name", name)
                .bind("id", recordId).bind("usedAt", now)
                .execute();
    }
}
