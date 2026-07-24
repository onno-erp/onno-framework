package su.onno.process;

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
    default String title() {
        return getClass().getSimpleName();
    }
}
