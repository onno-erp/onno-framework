package su.onno.process;

/**
 * Immediate process work implemented by application Java.
 *
 * @param <P> process payload type
 */
@FunctionalInterface
public interface AutomaticStep<P> {

    /**
     * Execute the step and return the payload to persist for the following node.
     *
     * <p>The returned payload must not be {@code null}. Returning it rather than requiring mutable
     * state lets process definitions use immutable records.</p>
     */
    P execute(P payload, ProcessExecutionContext context);
}
