package su.onno.process;

/** Join paired with one typed parallel fork. */
public final class ParallelJoinNode<
        P, S extends Enum<S> & ProcessStepKey, B extends Enum<B>>
        extends ProcessNode<P, S> {

    private final ParallelForkNode<P, S, B> fork;
    private ProcessNode<P, S> target;

    ParallelJoinNode(
            ProcessGraph<P, S> graph,
            S step,
            ParallelForkNode<P, S, B> fork) {
        super(graph, step);
        this.fork = fork;
    }

    /** Fork whose branch tokens this join synchronizes. */
    public ParallelForkNode<P, S, B> fork() {
        return fork;
    }

    /** Connect this synchronized join to its sole continuation. */
    public ParallelJoinNode<P, S, B> to(ProcessNode<P, S> target) {
        graph().connectSingle(this, this.target, target);
        this.target = target;
        return this;
    }

    ProcessNode<P, S> target() {
        return target;
    }

    @Override
    public ProcessNodeType type() {
        return ProcessNodeType.PARALLEL_JOIN;
    }
}
