package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit entry for one durable human task. */
public record ProcessWorkItemEventSnapshot(
        UUID id,
        UUID workItemId,
        UUID instanceId,
        WorkItemEventType type,
        String actor,
        String fromAssignee,
        String toAssignee,
        String reason,
        Instant occurredAt,
        int sequence
) {
}
