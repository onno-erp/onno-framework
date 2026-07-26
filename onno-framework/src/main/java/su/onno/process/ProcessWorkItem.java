package su.onno.process;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable human task exposed by {@link ProcessEngine}. */
public record ProcessWorkItem(
        UUID id,
        UUID instanceId,
        String definitionKey,
        String stepKey,
        String title,
        WorkItemStatus status,
        Set<String> candidateUsers,
        Set<String> candidateRoles,
        String assignee,
        Instant createdAt,
        Instant claimedAt,
        Instant completedAt,
        String outcome,
        List<String> outcomes
) {
}
