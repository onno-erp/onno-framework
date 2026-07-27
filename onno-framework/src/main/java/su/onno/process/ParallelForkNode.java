package su.onno.process;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immediate fork that activates every branch in a closed enum set. */
public final class ParallelForkNode<
        P, S extends Enum<S> & ProcessStepKey, B extends Enum<B>>
        extends ProcessNode<P, S> {

    private final Class<B> branchType;
    private final Map<B, ProcessNode<P, S>> branches;
    private ParallelJoinNode<P, S, B> join;

    ParallelForkNode(ProcessGraph<P, S> graph, S step, Class<B> branchType) {
        super(graph, step);
        this.branchType = Objects.requireNonNull(branchType, "branchType");
        this.branches = new EnumMap<>(branchType);
    }

    public Class<B> branchType() {
        return branchType;
    }

    /** Begin the route for one typed parallel branch. */
    public BranchTransition<P, S, B> on(B branch) {
        return new BranchTransition<>(this, Objects.requireNonNull(branch, "branch"));
    }

    /** The paired join that waits for every branch created by this fork. */
    public ParallelJoinNode<P, S, B> join() {
        return join;
    }

    void pairWith(ParallelJoinNode<P, S, B> join) {
        if (this.join != null) {
            throw new InvalidProcessDefinitionException(
                    "Parallel fork " + step().key() + " already has a paired join");
        }
        this.join = Objects.requireNonNull(join, "join");
    }

    void connect(B branch, ProcessNode<P, S> target) {
        graph().connect(this, target);
        if (branches.putIfAbsent(branch, target) != null) {
            throw new InvalidProcessDefinitionException(
                    "Parallel fork " + step().key()
                            + " already handles branch " + branch.name());
        }
    }

    ProcessNode<P, S> target(B branch) {
        return branches.get(branch);
    }

    Map<B, ProcessNode<P, S>> branches() {
        return Map.copyOf(branches);
    }

    @Override
    public ProcessNodeType type() {
        return ProcessNodeType.PARALLEL_FORK;
    }

    /** A typed, not-yet-connected parallel branch. */
    public static final class BranchTransition<
            P, S extends Enum<S> & ProcessStepKey, B extends Enum<B>> {

        private final ParallelForkNode<P, S, B> source;
        private final B branch;

        private BranchTransition(ParallelForkNode<P, S, B> source, B branch) {
            this.source = source;
            this.branch = branch;
        }

        /** Connect this branch to its first node. */
        public ParallelForkNode<P, S, B> to(ProcessNode<P, S> target) {
            source.connect(branch, target);
            return source;
        }
    }
}
