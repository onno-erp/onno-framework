package su.onno.process;

import java.util.Objects;

/** Durable wait-until node with one continuation. */
public final class TimerNode<P, S extends Enum<S> & ProcessStepKey>
        extends ProcessNode<P, S> {

    private final ProcessTimer<P> timer;
    private ProcessNode<P, S> target;

    TimerNode(ProcessGraph<P, S> graph, S step, ProcessTimer<P> timer) {
        super(graph, step);
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    public ProcessTimer<P> timer() {
        return timer;
    }

    /** Connect this timer to its sole continuation. */
    public TimerNode<P, S> to(ProcessNode<P, S> target) {
        graph().connectSingle(this, this.target, target);
        this.target = target;
        return this;
    }

    ProcessNode<P, S> target() {
        return target;
    }

    @Override
    public ProcessNodeType type() {
        return ProcessNodeType.TIMER;
    }
}
