package su.onno.process;

import su.onno.types.Ref;

/**
 * A typed unit of human work in a business process.
 *
 * @param <P> process payload type
 * @param <O> closed set of outcomes that can complete this task
 */
public interface HumanTask<P, O extends Enum<O>> {

    /** Enum class used to validate that the process route handles every possible outcome. */
    Class<O> outcomeType();

    /** Human-facing task title. */
    default String title(P payload) {
        return getClass().getSimpleName();
    }

    /** Candidate users/roles allowed to see and claim this task. */
    TaskAssignment assignment(P payload);

    /** Typed business object this task concerns; rendered as a direct link when present. */
    default Ref<?> subject(P payload) {
        return null;
    }

    /**
     * Human-readable snapshot for {@link #subject(Object)}, such as an order number.
     *
     * <p>The value is persisted with the task so its link remains understandable even when the
     * referenced record later changes.</p>
     */
    default String subjectLabel(P payload) {
        return null;
    }
}
