package su.onno.process;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable node that starts a typed child process and routes on its terminal step. */
public final class SubprocessNode<
        P,
        S extends Enum<S> & ProcessStepKey,
        C,
        CS extends Enum<CS> & ProcessStepKey>
        extends ProcessNode<P, S> {

    private final SubprocessCall<P, C, CS> call;
    private final Map<CS, ProcessNode<P, S>> terminalRoutes;
    private ProcessNode<P, S> cancellationTarget;

    SubprocessNode(ProcessGraph<P, S> graph, S step, SubprocessCall<P, C, CS> call) {
        super(graph, step);
        this.call = Objects.requireNonNull(call, "call");
        Objects.requireNonNull(call.definition(), "subprocess definition");
        this.terminalRoutes = new LinkedHashMap<>();
    }

    public SubprocessCall<P, C, CS> call() {
        return call;
    }

    /** Begin a route for one terminal step of the child definition. */
    public TerminalTransition<P, S, C, CS> on(CS terminalStep) {
        return new TerminalTransition<>(
                this, Objects.requireNonNull(terminalStep, "terminalStep"));
    }

    /** Begin the mandatory route used when the child instance is cancelled. */
    public CancellationTransition<P, S, C, CS> onCancellation() {
        return new CancellationTransition<>(this);
    }

    void connect(CS terminalStep, ProcessNode<P, S> target) {
        graph().connect(this, target);
        if (terminalRoutes.putIfAbsent(terminalStep, target) != null) {
            throw new InvalidProcessDefinitionException(
                    "Subprocess step " + step().key()
                            + " already handles child ending " + terminalStep.key());
        }
    }

    void connectCancellation(ProcessNode<P, S> target) {
        graph().connectSingle(this, cancellationTarget, target);
        cancellationTarget = target;
    }

    ProcessNode<P, S> target(CS terminalStep) {
        return terminalRoutes.get(terminalStep);
    }

    ProcessNode<P, S> cancellationTarget() {
        return cancellationTarget;
    }

    Map<CS, ProcessNode<P, S>> terminalRoutes() {
        return Map.copyOf(terminalRoutes);
    }

    @Override
    public ProcessNodeType type() {
        return ProcessNodeType.SUBPROCESS;
    }

    /** A typed, not-yet-connected child terminal route. */
    public static final class TerminalTransition<
            P,
            S extends Enum<S> & ProcessStepKey,
            C,
            CS extends Enum<CS> & ProcessStepKey> {

        private final SubprocessNode<P, S, C, CS> source;
        private final CS terminalStep;

        private TerminalTransition(
                SubprocessNode<P, S, C, CS> source, CS terminalStep) {
            this.source = source;
            this.terminalStep = terminalStep;
        }

        /** Connect this child ending to the parent's next node. */
        public SubprocessNode<P, S, C, CS> to(ProcessNode<P, S> target) {
            source.connect(terminalStep, target);
            return source;
        }
    }

    /** A not-yet-connected child-cancellation route. */
    public static final class CancellationTransition<
            P,
            S extends Enum<S> & ProcessStepKey,
            C,
            CS extends Enum<CS> & ProcessStepKey> {

        private final SubprocessNode<P, S, C, CS> source;

        private CancellationTransition(SubprocessNode<P, S, C, CS> source) {
            this.source = source;
        }

        /** Connect child cancellation to the parent's next node. */
        public SubprocessNode<P, S, C, CS> to(ProcessNode<P, S> target) {
            source.connectCancellation(target);
            return source;
        }
    }
}
