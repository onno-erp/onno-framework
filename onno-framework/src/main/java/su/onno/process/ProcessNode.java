package su.onno.process;

import java.util.Objects;

/** A typed node owned by a {@link ProcessGraph}. */
public abstract sealed class ProcessNode<P, S extends Enum<S> & ProcessStepKey>
        permits StartNode, HumanTaskNode, AutomaticNode, DecisionNode, TimerNode,
                ParallelForkNode, ParallelJoinNode, SubprocessNode, EndNode {

    private final ProcessGraph<P, S> graph;
    private final S step;

    ProcessNode(ProcessGraph<P, S> graph, S step) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.step = step;
    }

    final ProcessGraph<P, S> graph() {
        return graph;
    }

    /**
     * Step identity, or {@code null} for the synthetic start node.
     */
    public final S step() {
        return step;
    }

    /** Stable node kind for runtime dispatch and graph inspection. */
    public abstract ProcessNodeType type();
}
