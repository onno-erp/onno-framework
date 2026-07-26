package su.onno.process;

import java.time.Instant;

/**
 * One recorded process transition.
 *
 * @param from source step, or {@code null} for process start
 * @param to target step
 * @param outcome completed task outcome, or {@code null} for process start
 */
public record ProcessTransition<S extends Enum<S> & ProcessStepKey>(
        S from,
        S to,
        Enum<?> outcome,
        Instant occurredAt) {
}
