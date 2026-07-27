package su.onno.process;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable human task exposed by {@link ProcessEngine}. */
public record ProcessWorkItem(
        UUID id,
        UUID instanceId,
        UUID tokenId,
        String definitionKey,
        int definitionVersion,
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
    public ProcessWorkItem {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /** Source-compatible constructor for work items produced before execution tokens. */
    public ProcessWorkItem(
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
        this(
                id, instanceId, null, definitionKey, 1, stepKey, title, status,
                assigneeId, assignee, subject, createdAt, claimedAt, completedAt, outcome, outcomes);
    }
}
