package su.onno.process;

/** Terminal node of a typed process graph. */
public final class EndNode<P, S extends Enum<S> & ProcessStepKey> extends ProcessNode<P, S> {

    EndNode(ProcessGraph<P, S> graph, S step) {
        super(graph, step);
    }
}
