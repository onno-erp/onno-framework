package su.onno.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A running instance created by the prototype {@link InMemoryProcessEngine}. */
public final class ProcessInstance<P, S extends Enum<S> & ProcessStepKey> {

    private final UUID id = UUID.randomUUID();
    private final P payload;
    private final ProcessGraph<P, S> graph;
    private final List<ProcessTransition<S>> history = new ArrayList<>();
    private ProcessNode<P, S> current;
    private ProcessStatus status;

    ProcessInstance(P payload, ProcessGraph<P, S> graph, ProcessNode<P, S> current) {
        this.payload = Objects.requireNonNull(payload, "payload");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.current = Objects.requireNonNull(current, "current");
        this.status = current instanceof EndNode<?, ?>
                ? ProcessStatus.COMPLETED
                : ProcessStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public P payload() {
        return payload;
    }

    public S currentStep() {
        return current.step();
    }

    public ProcessStatus status() {
        return status;
    }

    public List<ProcessTransition<S>> history() {
        return List.copyOf(history);
    }

    ProcessGraph<P, S> graph() {
        return graph;
    }

    ProcessNode<P, S> current() {
        return current;
    }

    void advance(ProcessNode<P, S> target, ProcessTransition<S> transition) {
        current = target;
        history.add(transition);
        if (target instanceof EndNode<?, ?>) {
            status = ProcessStatus.COMPLETED;
        }
    }
}
