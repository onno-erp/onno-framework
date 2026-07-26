package su.onno.process;

/**
 * Typed definition of one business-process route.
 *
 * @param <P> process payload type
 * @param <S> enum containing this process's stable step keys
 */
public abstract class ProcessDefinition<P, S extends Enum<S> & ProcessStepKey> {

    private final String key;
    private final Class<P> payloadType;
    private volatile ProcessGraph<P, S> graph;

    protected ProcessDefinition(String key, Class<P> payloadType) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Process definition key must not be blank");
        }
        this.key = key;
        this.payloadType = java.util.Objects.requireNonNull(payloadType, "payloadType");
    }

    /** Stable persisted definition identity. Never derived from a display title or class name. */
    public final String key() {
        return key;
    }

    /** Payload type used by the durable runtime's JSON codec. */
    public final Class<P> payloadType() {
        return payloadType;
    }

    /** Human-facing process title. */
    public String title() {
        return getClass().getSimpleName();
    }

    /**
     * Candidate users/roles allowed to start this process through a generic user-facing boundary.
     * Ordinary trusted Java services may still call {@link ProcessEngine#start} directly.
     */
    public abstract TaskAssignment startAssignment(P payload);

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
