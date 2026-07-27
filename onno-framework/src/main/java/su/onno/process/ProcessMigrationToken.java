package su.onno.process;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed source token presented to a process-definition migration.
 *
 * @param tokenId durable token identity preserved by the migration
 * @param step source definition step
 * @param parentTokenId parent fork token, when this is a parallel branch token
 * @param branchKey stable parallel branch key, when present
 */
public record ProcessMigrationToken<S extends Enum<S> & ProcessStepKey>(
        UUID tokenId,
        S step,
        UUID parentTokenId,
        String branchKey) {

    public ProcessMigrationToken {
        tokenId = Objects.requireNonNull(tokenId, "tokenId");
        step = Objects.requireNonNull(step, "step");
    }
}
