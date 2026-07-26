package su.onno.process;

/** Synthetic start node of a process graph. */
public final class StartNode<P, S extends Enum<S> & ProcessStepKey> extends ProcessNode<P, S> {

    private ProcessNode<P, S> target;

    StartNode(ProcessGraph<P, S> graph) {
        super(graph, null);
    }

    /** Connect the process start to its first typed step. */
    public StartNode<P, S> to(ProcessNode<P, S> target) {
        graph().connectStart(this, target);
        this.target = target;
        return this;
    }

    ProcessNode<P, S> target() {
        return target;
    }
}
