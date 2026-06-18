package su.onno.performance;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * JDK Flight Recorder instrumentation helpers for onno framework operations.
 */
public final class OnnoPerformance {

    private OnnoPerformance() {
    }

    public static <T> T record(String operation, Supplier<T> action) {
        return record(operation, 0, action);
    }

    public static <T> T record(String operation, long itemCount, Supplier<T> action) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");
        OnnoOperationEvent event = new OnnoOperationEvent(operation, Math.max(0, itemCount));
        event.begin();
        try {
            return action.get();
        } finally {
            event.end();
            event.commit();
        }
    }

    public static void record(String operation, Runnable action) {
        record(operation, 0, action);
    }

    public static void record(String operation, long itemCount, Runnable action) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");
        OnnoOperationEvent event = new OnnoOperationEvent(operation, Math.max(0, itemCount));
        event.begin();
        try {
            action.run();
        } finally {
            event.end();
            event.commit();
        }
    }
}
