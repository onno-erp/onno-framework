package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit entry for one durable human task. */
public record ProcessWorkItemEventSnapshot(
        UUID id,
        UUID workItemId,
        UUID instanceId,
        WorkItemEventType type,
        ProcessActorId actorId,
        String actor,
        ProcessActorId fromAssigneeId,
        String fromAssignee,
        ProcessActorId toAssigneeId,
        String toAssignee,
        String reason,
        Instant occurredAt,
        int sequence
) {
}
