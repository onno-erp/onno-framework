package su.onno.process;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Target payload and target step for every durable source token.
 *
 * <p>Token ids remain stable so fork/branch ancestry and wait correlation survive migration.</p>
 */
public record ProcessMigrationResult<
        P, S extends Enum<S> & ProcessStepKey>(
        P payload,
        Map<UUID, S> tokenSteps) {

    public ProcessMigrationResult {
        payload = Objects.requireNonNull(payload, "payload");
        tokenSteps = tokenSteps == null ? Map.of() : Map.copyOf(tokenSteps);
        tokenSteps.forEach((tokenId, step) -> {
            Objects.requireNonNull(tokenId, "token id");
            Objects.requireNonNull(step, "target step");
        });
    }
}
