package su.onno.process;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence-oriented view of a durable process instance. */
public record ProcessSnapshot(
        UUID id,
        String definitionKey,
        int definitionVersion,
        String currentStep,
        List<String> activeSteps,
        ProcessStatus status,
        UUID rootInstanceId,
        UUID parentInstanceId,
        UUID parentTokenId,
        ProcessActorId startedById,
        String startedBy,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        Instant cancelledAt,
        String cancelReason,
        int version
) {
    public ProcessSnapshot {
        activeSteps = activeSteps == null ? List.of() : List.copyOf(activeSteps);
    }

    /** Source-compatible constructor for snapshots produced by the original single-token engine. */
    public ProcessSnapshot(
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
        this(
                id, definitionKey, 1, currentStep,
                currentStep == null || status != ProcessStatus.ACTIVE
                        ? List.of() : List.of(currentStep),
                status, id, null, null, startedById, startedBy, startedAt, updatedAt,
                status == ProcessStatus.COMPLETED ? updatedAt : null,
                status == ProcessStatus.CANCELLED ? updatedAt : null,
                null, version);
    }
}
