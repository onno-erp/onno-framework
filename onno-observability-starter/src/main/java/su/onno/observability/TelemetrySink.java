package su.onno.observability;

/**
 * Non-blocking telemetry boundary. Implementations must never make business operations fail.
 */
@FunctionalInterface
public interface TelemetrySink {

    /** Accept an event for best-effort asynchronous delivery. */
    void accept(TelemetryEvent event);

    /** Browser session sample rate advertised by the UI. */
    default double browserSampleRate() {
        return 1.0;
    }
}
