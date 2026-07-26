package su.onno.process;

import java.time.Instant;
import java.util.UUID;

/** One durable, append-only transition in a process instance's audit history. */
public record ProcessTransitionSnapshot(
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
}
