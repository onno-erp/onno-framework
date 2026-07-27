package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** One durable, append-only transition in a process instance's audit history. */
public record ProcessTransitionSnapshot(
        UUID id,
        UUID instanceId,
        UUID tokenId,
        int definitionVersion,
        ProcessTransitionType type,
        String fromStep,
        String toStep,
        String outcome,
        ProcessActorId actorId,
        String actor,
        Instant occurredAt,
        int sequence
) {
    /** Source-compatible constructor for the original human-task-only transition shape. */
    public ProcessTransitionSnapshot(
            UUID id,
            UUID instanceId,
            String fromStep,
            String toStep,
            String outcome,
            ProcessActorId actorId,
            String actor,
            Instant occurredAt,
            int sequence
    ) {
        this(
                id, instanceId, null, 1,
                fromStep == null ? ProcessTransitionType.START : ProcessTransitionType.HUMAN_TASK,
                fromStep, toStep, outcome, actorId, actor, occurredAt, sequence);
    }
}
