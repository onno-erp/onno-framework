package su.onno.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed business-process route graph.
 *
 * <p>Application code connects node handles rather than string names. The graph becomes immutable
 * after definition validation.</p>
 */
public final class ProcessGraph<P, S extends Enum<S> & ProcessStepKey> {

    private final StartNode<P, S> start = new StartNode<>(this);
    private final Map<S, ProcessNode<P, S>> nodes = new LinkedHashMap<>();
    private boolean sealed;

    public StartNode<P, S> start() {
        return start;
    }

    /** Add a typed human-task node. */
    public <O extends Enum<O>> HumanTaskNode<P, S, O> human(S step, HumanTask<P, O> task) {
        ensureMutable();
        var node = new HumanTaskNode<>(this, requireStep(step), task);
        add(node);
        return node;
    }

    /** Add a terminal node. A graph may have multiple meaningful endings. */
    public EndNode<P, S> end(S step) {
        ensureMutable();
        var node = new EndNode<P, S>(this, requireStep(step));
        add(node);
        return node;
    }

    /** Resolve a node by its typed step key. */
    public ProcessNode<P, S> node(S step) {
        return nodes.get(step);
    }

    /** Declared route nodes in definition order, excluding the synthetic start. */
    public List<ProcessNode<P, S>> nodes() {
        return List.copyOf(nodes.values());
    }

    void connectStart(StartNode<P, S> source, ProcessNode<P, S> target) {
        ensureMutable();
        requireOwned(source);
        requireOwned(target);
        if (source.target() != null) {
            throw new InvalidProcessDefinitionException("Process start already has a target");
        }
    }

    void connect(ProcessNode<P, S> source, ProcessNode<P, S> target) {
        ensureMutable();
        requireOwned(source);
        requireOwned(target);
    }

    void validateAndSeal() {
        ensureMutable();
        if (nodes.isEmpty()) {
            throw new InvalidProcessDefinitionException("Process graph has no route nodes");
        }
        if (start.target() == null) {
            throw new InvalidProcessDefinitionException("Process start has no target");
        }

        Set<String> persistentKeys = new LinkedHashSet<>();
        for (ProcessNode<P, S> node : nodes.values()) {
            String key = node.step().key();
            if (key == null || key.isBlank()) {
                throw new InvalidProcessDefinitionException(
                        "Step " + node.step().name() + " has a blank persistent key");
            }
            if (!persistentKeys.add(key)) {
                throw new InvalidProcessDefinitionException("Duplicate persistent step key: " + key);
            }
            if (node instanceof HumanTaskNode<?, ?, ?> taskNode) {
                validateOutcomes(taskNode);
            }
        }

        Set<ProcessNode<P, S>> reachable = reachableNodes();
        List<String> unreachable = nodes.values().stream()
                .filter(node -> !reachable.contains(node))
                .map(node -> node.step().key())
                .toList();
        if (!unreachable.isEmpty()) {
            throw new InvalidProcessDefinitionException("Unreachable process steps: " + unreachable);
        }
        sealed = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateOutcomes(HumanTaskNode<?, ?, ?> rawNode) {
        HumanTaskNode node = rawNode;
        Class<? extends Enum> outcomeType = node.task().outcomeType();
        Set<? extends Enum> expected = EnumSet.allOf(outcomeType);
        Set<?> actual = node.transitions().keySet();
        if (!actual.equals(expected)) {
            List<String> missing = new ArrayList<>();
            for (Enum<?> outcome : expected) {
                if (!actual.contains(outcome)) {
                    missing.add(outcome.name());
                }
            }
            throw new InvalidProcessDefinitionException(
                    "Step " + ((ProcessStepKey) node.step()).key()
                            + " does not handle outcomes " + missing);
        }
    }

    private Set<ProcessNode<P, S>> reachableNodes() {
        Set<ProcessNode<P, S>> visited = new LinkedHashSet<>();
        ArrayDeque<ProcessNode<P, S>> queue = new ArrayDeque<>();
        queue.add(start.target());
        while (!queue.isEmpty()) {
            ProcessNode<P, S> node = queue.removeFirst();
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof HumanTaskNode<?, ?, ?> rawTask) {
                for (ProcessNode<P, S> target : taskTargets(rawTask)) {
                    queue.addLast(target);
                }
            }
        }
        return visited;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ProcessNode<P, S>> taskTargets(HumanTaskNode<?, ?, ?> rawTask) {
        HumanTaskNode task = rawTask;
        return List.copyOf(task.transitions().values());
    }

    private void add(ProcessNode<P, S> node) {
        ProcessNode<P, S> previous = nodes.putIfAbsent(node.step(), node);
        if (previous != null) {
            throw new InvalidProcessDefinitionException(
                    "Duplicate process step: " + node.step().name());
        }
    }

    private S requireStep(S step) {
        return Objects.requireNonNull(step, "step");
    }

    private void requireOwned(ProcessNode<P, S> node) {
        Objects.requireNonNull(node, "target");
        if (node.graph() != this) {
            throw new InvalidProcessDefinitionException(
                    "Cannot connect nodes from different process graphs");
        }
    }

    private void ensureMutable() {
        if (sealed) {
            throw new IllegalStateException("Process graph is already sealed");
        }
    }
}
