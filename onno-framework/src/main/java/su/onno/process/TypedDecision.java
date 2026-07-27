package su.onno.process;

/**
 * Immediate, side-effect-free choice with a closed enum result.
 *
 * @param <P> process payload type
 * @param <O> exhaustive set of decision outcomes
 */
public interface TypedDecision<P, O extends Enum<O>> {

    /** Enum class used to validate that every possible result has a route. */
    Class<O> outcomeType();

    /** Select the route for the current payload. */
    O decide(P payload);
}
