package su.onno.process;

/**
 * Typed invocation of a child process definition.
 *
 * @param <P> parent payload type
 * @param <C> child payload type
 * @param <CS> child definition's step-key enum
 */
public interface SubprocessCall<
        P, C, CS extends Enum<CS> & ProcessStepKey> {

    /** Child definition to start. Its key and version are persisted with the child instance. */
    ProcessDefinition<C, CS> definition();

    /** Build the child payload from the current parent payload. */
    C payload(P parentPayload);

    /**
     * Merge the completed child payload back into the parent.
     *
     * <p>The default keeps the parent unchanged, which is suitable when the child communicates
     * through its own domain records.</p>
     */
    default P merge(P parentPayload, C completedChildPayload) {
        return parentPayload;
    }
}
