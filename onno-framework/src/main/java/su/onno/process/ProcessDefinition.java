package su.onno.process;

/**
 * Typed definition of one business-process route.
 *
 * @param <P> process payload type
 * @param <S> enum containing this process's stable step keys
 */
public abstract class ProcessDefinition<P, S extends Enum<S> & ProcessStepKey> {

    private volatile ProcessGraph<P, S> graph;

    /** Declare tasks, endings, and typed transitions. Called once on first graph access. */
    protected abstract void define(ProcessGraph<P, S> graph);

    /** Validated immutable graph for this definition. */
    public final ProcessGraph<P, S> graph() {
        ProcessGraph<P, S> current = graph;
        if (current == null) {
            synchronized (this) {
                current = graph;
                if (current == null) {
                    current = new ProcessGraph<>();
                    define(current);
                    current.validateAndSeal();
                    graph = current;
                }
            }
        }
        return current;
    }
}
