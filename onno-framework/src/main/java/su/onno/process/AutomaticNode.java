package su.onno.process;

import java.util.Objects;

/** Immediate node that executes application code and follows one route. */
public final class AutomaticNode<P, S extends Enum<S> & ProcessStepKey>
        extends ProcessNode<P, S> {

    private final AutomaticStep<P> action;
    private ProcessNode<P, S> target;

    AutomaticNode(ProcessGraph<P, S> graph, S step, AutomaticStep<P> action) {
        super(graph, step);
        this.action = Objects.requireNonNull(action, "action");
    }

    public AutomaticStep<P> action() {
        return action;
    }

    /** Connect this immediate step to its sole next node. */
    public AutomaticNode<P, S> to(ProcessNode<P, S> target) {
        graph().connectSingle(this, this.target, target);
        this.target = target;
        return this;
    }

    ProcessNode<P, S> target() {
        return target;
    }

    @Override
    public ProcessNodeType type() {
        return ProcessNodeType.AUTOMATIC;
    }
}
