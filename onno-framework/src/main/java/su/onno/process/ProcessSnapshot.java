package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** Persistence-oriented view of a durable process instance. */
public record ProcessSnapshot(
        UUID id,
        String definitionKey,
        String currentStep,
        ProcessStatus status,
        ProcessActorId startedById,
        String startedBy,
        Instant startedAt,
        Instant updatedAt,
        int version
) {
}
