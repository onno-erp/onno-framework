package su.onno.process;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immediate branching node driven by a typed, exhaustive decision. */
public final class DecisionNode<
        P, S extends Enum<S> & ProcessStepKey, O extends Enum<O>>
        extends ProcessNode<P, S> {

    private final TypedDecision<P, O> decision;
    private final Map<O, ProcessNode<P, S>> transitions;

    DecisionNode(ProcessGraph<P, S> graph, S step, TypedDecision<P, O> decision) {
        super(graph, step);
        this.decision = Objects.requireNonNull(decision, "decision");
        this.transitions = new EnumMap<>(
                Objects.requireNonNull(decision.outcomeType(), "decision outcomeType"));
    }

    public TypedDecision<P, O> decision() {
        return decision;
    }

    /** Begin a route for one possible decision result. */
    public OutcomeTransition<P, S, O> on(O outcome) {
        return new OutcomeTransition<>(this, Objects.requireNonNull(outcome, "outcome"));
    }

    void connect(O outcome, ProcessNode<P, S> target) {
        graph().connect(this, target);
        if (transitions.putIfAbsent(outcome, target) != null) {
            throw new InvalidProcessDefinitionException(
                    "Step " + step().key() + " already handles outcome " + outcome.name());
        }
    }

    ProcessNode<P, S> target(O outcome) {
        return transitions.get(outcome);
    }

    Map<O, ProcessNode<P, S>> transitions() {
        return Map.copyOf(transitions);
    }

    @Override
    public ProcessNodeType type() {
        return ProcessNodeType.DECISION;
    }

    /** A typed, not-yet-connected decision outcome. */
    public static final class OutcomeTransition<
            P, S extends Enum<S> & ProcessStepKey, O extends Enum<O>> {

        private final DecisionNode<P, S, O> source;
        private final O outcome;

        private OutcomeTransition(DecisionNode<P, S, O> source, O outcome) {
            this.source = source;
            this.outcome = outcome;
        }

        /** Connect this decision result to its next node. */
        public DecisionNode<P, S, O> to(ProcessNode<P, S> target) {
            source.connect(outcome, target);
            return source;
        }
    }
}
