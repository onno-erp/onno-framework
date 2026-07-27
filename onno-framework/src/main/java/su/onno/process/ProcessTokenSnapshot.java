package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** Inspectable durable execution token, including timer and subprocess waits. */
public record ProcessTokenSnapshot(
        UUID id,
        UUID instanceId,
        UUID parentTokenId,
        String branchKey,
        String stepKey,
        ProcessNodeType nodeType,
        ProcessTokenStatus status,
        Instant dueAt,
        UUID childInstanceId,
        Instant enteredAt,
        Instant updatedAt,
        int attempt,
        int version
) {
}
