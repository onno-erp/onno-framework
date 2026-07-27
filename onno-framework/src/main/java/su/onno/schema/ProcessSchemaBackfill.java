package su.onno.schema;

import org.jdbi.v3.core.Jdbi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal data upgrade for process instances created by the original single-step runtime.
 *
 * <p>Process execution tokens became the source of truth after durable fork/join, timers, and
 * subprocesses were introduced. An old active instance can only be waiting at a human task, so one
 * token can be reconstructed safely from its persisted current step and linked to its open work
 * item. The upgrade is idempotent and runs after structural schema reconciliation.</p>
 */
final class ProcessSchemaBackfill {

    private ProcessSchemaBackfill() {
    }

    static void run(Jdbi jdbi) {
        jdbi.useTransaction(handle -> {
            List<LegacyInstance> instances = handle.createQuery("""
                    select i._id, i._current_step, i._started_at, i._updated_at
                      from onno_process_instances i
                     where i._status = 'ACTIVE'
                       and not exists (
                           select 1 from onno_process_tokens t where t._instance_id = i._id
                       )
                    """)
                    .map((rs, context) -> new LegacyInstance(
                            rs.getObject("_id", UUID.class),
                            rs.getString("_current_step"),
                            instant(rs.getTimestamp("_started_at")),
                            instant(rs.getTimestamp("_updated_at"))))
                    .list();

            for (LegacyInstance instance : instances) {
                UUID tokenId = UUID.randomUUID();
                Instant enteredAt = instance.updatedAt == null
                        ? instance.startedAt : instance.updatedAt;
                handle.createUpdate("""
                        insert into onno_process_tokens
                            (_id, _instance_id, _step_key, _node_type, _status,
                             _entered_at, _updated_at, _attempt, _version)
                        values (:id, :instance, :step, 'HUMAN_TASK', 'WAITING_HUMAN',
                                :enteredAt, :enteredAt, 0, 0)
                        """)
                        .bind("id", tokenId)
                        .bind("instance", instance.id)
                        .bind("step", instance.currentStep)
                        .bind("enteredAt", enteredAt)
                        .execute();
                handle.createUpdate("""
                        update onno_process_work_items
                           set _token_id = :token
                         where _instance_id = :instance
                           and _token_id is null
                           and _status in ('OPEN', 'CLAIMED')
                        """)
                        .bind("token", tokenId)
                        .bind("instance", instance.id)
                        .execute();
            }
        });
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record LegacyInstance(
            UUID id,
            String currentStep,
            Instant startedAt,
            Instant updatedAt
    ) {
    }
}
