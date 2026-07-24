package su.onno.process;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Route node that waits for a {@link HumanTask} to complete with one of its typed outcomes. */
public final class HumanTaskNode<P, S extends Enum<S> & ProcessStepKey, O extends Enum<O>>
        extends ProcessNode<P, S> {

    private final HumanTask<P, O> task;
    private final Map<O, ProcessNode<P, S>> transitions;

    HumanTaskNode(ProcessGraph<P, S> graph, S step, HumanTask<P, O> task) {
        super(graph, step);
        this.task = Objects.requireNonNull(task, "task");
        this.transitions = new EnumMap<>(task.outcomeType());
    }

    public HumanTask<P, O> task() {
        return task;
    }

    /**
     * Begin a transition for one valid task outcome.
     *
     * <p>The returned handle accepts only nodes from the same process payload and step-key types.
     * Definition validation additionally rejects cross-graph handles.</p>
     */
    public OutcomeTransition<P, S, O> on(O outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return new OutcomeTransition<>(this, outcome);
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

    /** A typed, not-yet-connected task outcome. */
    public static final class OutcomeTransition<
            P, S extends Enum<S> & ProcessStepKey, O extends Enum<O>> {

        private final HumanTaskNode<P, S, O> source;
        private final O outcome;

        private OutcomeTransition(HumanTaskNode<P, S, O> source, O outcome) {
            this.source = source;
            this.outcome = outcome;
        }

        /** Connect this outcome to its next node. */
        public HumanTaskNode<P, S, O> to(ProcessNode<P, S> target) {
            source.connect(outcome, target);
            return source;
        }
    }
}
