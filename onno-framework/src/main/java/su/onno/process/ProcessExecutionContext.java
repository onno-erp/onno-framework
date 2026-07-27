package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** Stable execution identity supplied to an automatic process step. */
public record ProcessExecutionContext(
        UUID instanceId,
        UUID tokenId,
        String definitionKey,
        int definitionVersion,
        int attempt,
        Instant enteredAt
) {

    /**
     * Stable key for idempotent work or an outbox message produced by this execution attempt.
     */
    public String idempotencyKey() {
        return definitionKey + ":" + instanceId + ":" + tokenId + ":" + attempt;
    }
}
