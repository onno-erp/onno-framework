package su.onno.process;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Executable prototype runtime for typed process definitions.
 *
 * <p>This engine intentionally does not persist instances or create user inbox tasks. It proves the
 * public route language and transition semantics before those storage and UI contracts are fixed.</p>
 */
public final class InMemoryProcessEngine {

    private final Clock clock;

    public InMemoryProcessEngine() {
        this(Clock.systemUTC());
    }

    public InMemoryProcessEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Start a validated definition and enter its first route node. */
    public <P, S extends Enum<S> & ProcessStepKey> ProcessInstance<P, S> start(
            ProcessDefinition<P, S> definition,
            P payload) {
        ProcessGraph<P, S> graph = Objects.requireNonNull(definition, "definition").graph();
        ProcessNode<P, S> first = graph.start().target();
        ProcessInstance<P, S> instance = new ProcessInstance<>(payload, graph, first);
        instance.advance(first, new ProcessTransition<>(null, first.step(), null, Instant.now(clock)));
        return instance;
    }

    /**
     * Complete the currently active task with an outcome accepted by that task's Java type.
     */
    public <P, S extends Enum<S> & ProcessStepKey, O extends Enum<O>> void complete(
            ProcessInstance<P, S> instance,
            HumanTaskNode<P, S, O> task,
            O outcome) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(outcome, "outcome");

        synchronized (instance) {
            if (instance.status() != ProcessStatus.ACTIVE) {
                throw new IllegalStateException("Process instance is already completed");
            }
            if (instance.graph() != task.graph()) {
                throw new IllegalArgumentException("Task belongs to a different process definition");
            }
            if (instance.current() != task) {
                throw new IllegalStateException(
                        "Step " + task.step().key() + " is not active; current step is "
                                + instance.currentStep().key());
            }
            ProcessNode<P, S> target = task.target(outcome);
            if (target == null) {
                throw new IllegalArgumentException(
                        "Outcome " + outcome.name() + " has no transition from " + task.step().key());
            }
            instance.advance(target, new ProcessTransition<>(
                    task.step(), target.step(), outcome, Instant.now(clock)));
        }
    }
}
