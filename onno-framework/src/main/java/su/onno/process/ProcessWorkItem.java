package su.onno.process;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable human task exposed by {@link ProcessEngine}. */
public record ProcessWorkItem(
        UUID id,
        UUID instanceId,
        String definitionKey,
        String stepKey,
        String title,
        WorkItemStatus status,
        ProcessActorId assigneeId,
        String assignee,
        ProcessDomainLink subject,
        Instant createdAt,
        Instant claimedAt,
        Instant completedAt,
        String outcome,
        List<String> outcomes
) {
}
