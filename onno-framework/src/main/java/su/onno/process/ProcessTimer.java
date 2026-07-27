package su.onno.process;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

/** Calculates when a durable timer node becomes eligible to continue. */
@FunctionalInterface
public interface ProcessTimer<P> {

    /** Calculate an absolute due time from the payload and the runtime's current time. */
    Instant dueAt(P payload, Instant now);

    /** Create a timer due after a fixed duration from the time the node is entered. */
    static <P> ProcessTimer<P> after(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Timer delay must not be negative");
        }
        return (payload, now) -> Objects.requireNonNull(now, "now").plus(delay);
    }

    /** Create a timer due at an absolute time derived from the process payload. */
    static <P> ProcessTimer<P> at(Function<P, Instant> dueAt) {
        Objects.requireNonNull(dueAt, "dueAt");
        return (payload, now) -> Objects.requireNonNull(
                dueAt.apply(payload), "timer due time");
    }
}
