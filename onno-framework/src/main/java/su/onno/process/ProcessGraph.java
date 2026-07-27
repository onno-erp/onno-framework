package su.onno.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
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

    /** Add an immediate application-code step with one continuation. */
    public AutomaticNode<P, S> automatic(S step, AutomaticStep<P> action) {
        ensureMutable();
        var node = new AutomaticNode<>(this, requireStep(step), action);
        add(node);
        return node;
    }

    /** Add an immediate, exhaustive typed decision. */
    public <O extends Enum<O>> DecisionNode<P, S, O> decision(
            S step, TypedDecision<P, O> decision) {
        ensureMutable();
        var node = new DecisionNode<>(this, requireStep(step), decision);
        add(node);
        return node;
    }

    /** Add a durable timer wait with one continuation. */
    public TimerNode<P, S> timer(S step, ProcessTimer<P> timer) {
        ensureMutable();
        var node = new TimerNode<>(this, requireStep(step), timer);
        add(node);
        return node;
    }

    /**
     * Add a typed parallel fork and its paired join.
     *
     * <p>Declare every branch with {@link ParallelForkNode#on(Enum)} and connect the continuation
     * through {@code fork.join().to(next)}.</p>
     */
    public <B extends Enum<B>> ParallelForkNode<P, S, B> parallel(
            S forkStep, Class<B> branchType, S joinStep) {
        ensureMutable();
        var fork = new ParallelForkNode<>(this, requireStep(forkStep), branchType);
        add(fork);
        var join = new ParallelJoinNode<>(this, requireStep(joinStep), fork);
        try {
            add(join);
        } catch (RuntimeException failure) {
            nodes.remove(fork.step(), fork);
            throw failure;
        }
        fork.pairWith(join);
        return fork;
    }

    /** Add a durable typed child-process call. */
    public <C, CS extends Enum<CS> & ProcessStepKey> SubprocessNode<P, S, C, CS> subprocess(
            S step, SubprocessCall<P, C, CS> call) {
        ensureMutable();
        var node = new SubprocessNode<>(this, requireStep(step), call);
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

    /** Resolve a node by its stable persisted step key. */
    public ProcessNode<P, S> nodeByKey(String key) {
        return nodes.values().stream()
                .filter(node -> node.step().key().equals(key))
                .findFirst()
                .orElse(null);
    }

    /** Declared route nodes in definition order, excluding the synthetic start. */
    public List<ProcessNode<P, S>> nodes() {
        return List.copyOf(nodes.values());
    }

    /** Enum class that owns this graph's typed step keys. */
    @SuppressWarnings("unchecked")
    public Class<S> stepType() {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("Process graph has no route nodes");
        }
        return (Class<S>) nodes.values().iterator().next().step().getDeclaringClass();
    }

    /** Immutable, serialization-friendly structural description of this validated graph. */
    public ProcessGraphDescriptor descriptor() {
        if (!sealed) {
            throw new IllegalStateException("Process graph is not sealed");
        }
        List<ProcessNodeDescriptor> descriptors = nodes.values().stream()
                .map(this::describe)
                .toList();
        return new ProcessGraphDescriptor(start.target().step().key(), descriptors);
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

    void connectSingle(
            ProcessNode<P, S> source,
            ProcessNode<P, S> existingTarget,
            ProcessNode<P, S> target) {
        connect(source, target);
        if (existingTarget != null) {
            throw new InvalidProcessDefinitionException(
                    "Step " + source.step().key() + " already has a target");
        }
    }

    void validateAndSeal() {
        ensureMutable();
        if (nodes.isEmpty()) {
            throw new InvalidProcessDefinitionException("Process graph has no route nodes");
        }
        if (start.target() == null) {
            throw new InvalidProcessDefinitionException("Process start has no target");
        }

        validateStepKeysAndRoutes();

        Set<ProcessNode<P, S>> reachable = reachableNodes();
        List<String> unreachable = nodes.values().stream()
                .filter(node -> !reachable.contains(node))
                .map(node -> node.step().key())
                .toList();
        if (!unreachable.isEmpty()) {
            throw new InvalidProcessDefinitionException("Unreachable process steps: " + unreachable);
        }

        validateParallelBranches();
        validateNoImmediateCycles();
        sealed = true;
    }

    private void validateStepKeysAndRoutes() {
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

            switch (node.type()) {
                case HUMAN_TASK -> validateHumanOutcomes((HumanTaskNode<?, ?, ?>) node);
                case AUTOMATIC -> requireSingleTarget(node, ((AutomaticNode<?, ?>) node).target());
                case DECISION -> validateDecisionOutcomes((DecisionNode<?, ?, ?>) node);
                case TIMER -> requireSingleTarget(node, ((TimerNode<?, ?>) node).target());
                case PARALLEL_FORK -> validateForkBranches((ParallelForkNode<?, ?, ?>) node);
                case PARALLEL_JOIN ->
                        requireSingleTarget(node, ((ParallelJoinNode<?, ?, ?>) node).target());
                case SUBPROCESS -> validateSubprocessRoutes((SubprocessNode<?, ?, ?, ?>) node);
                case START, END -> {
                    // Start is validated separately; endings deliberately have no outgoing route.
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateHumanOutcomes(HumanTaskNode<?, ?, ?> rawNode) {
        HumanTaskNode node = rawNode;
        validateExhaustiveRoutes(
                ((ProcessStepKey) node.step()).key(),
                node.task().outcomeType(),
                node.transitions().keySet(),
                "outcomes");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateDecisionOutcomes(DecisionNode<?, ?, ?> rawNode) {
        DecisionNode node = rawNode;
        validateExhaustiveRoutes(
                ((ProcessStepKey) node.step()).key(),
                node.decision().outcomeType(),
                node.transitions().keySet(),
                "outcomes");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateForkBranches(ParallelForkNode<?, ?, ?> rawNode) {
        ParallelForkNode node = rawNode;
        validateExhaustiveRoutes(
                ((ProcessStepKey) node.step()).key(),
                node.branchType(),
                node.branches().keySet(),
                "branches");
        if (node.join() == null || node.join().fork() != node) {
            throw new InvalidProcessDefinitionException(
                    "Parallel fork " + ((ProcessStepKey) node.step()).key()
                            + " has no paired join");
        }
    }

    private static <E extends Enum<E>> void validateExhaustiveRoutes(
            String stepKey, Class<E> enumType, Set<?> actual, String routeKind) {
        Objects.requireNonNull(enumType, "outcome type");
        Set<E> expected = EnumSet.allOf(enumType);
        if (!actual.equals(expected)) {
            List<String> missing = expected.stream()
                    .filter(value -> !actual.contains(value))
                    .map(Enum::name)
                    .toList();
            throw new InvalidProcessDefinitionException(
                    "Step " + stepKey + " does not handle " + routeKind + " " + missing);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateSubprocessRoutes(SubprocessNode<?, ?, ?, ?> rawNode) {
        SubprocessNode node = rawNode;
        ProcessDefinition child = Objects.requireNonNull(
                node.call().definition(), "subprocess definition");
        ProcessGraph childGraph = child.graph();
        Set<Enum<?>> expected = new LinkedHashSet<>();
        for (Object rawChildNode : childGraph.nodes()) {
            ProcessNode childNode = (ProcessNode) rawChildNode;
            if (childNode instanceof EndNode<?, ?>) {
                expected.add((Enum<?>) childNode.step());
            }
        }

        Set<?> actual = node.terminalRoutes().keySet();
        List<String> missing = expected.stream()
                .filter(step -> !actual.contains(step))
                .map(ProcessGraph::stepKey)
                .toList();
        List<String> invalid = actual.stream()
                .filter(step -> !expected.contains(step))
                .map(step -> stepKey((Enum<?>) step))
                .toList();
        if (!missing.isEmpty() || !invalid.isEmpty()) {
            throw new InvalidProcessDefinitionException(
                    "Subprocess step " + ((ProcessStepKey) node.step()).key()
                            + " must route every child ending; missing=" + missing
                            + ", non-terminal=" + invalid);
        }
        if (node.cancellationTarget() == null) {
            throw new InvalidProcessDefinitionException(
                    "Subprocess step " + ((ProcessStepKey) node.step()).key()
                            + " has no cancellation route");
        }
    }

    private static String stepKey(Enum<?> step) {
        return ((ProcessStepKey) step).key();
    }

    private static void requireSingleTarget(ProcessNode<?, ?> node, ProcessNode<?, ?> target) {
        if (target == null) {
            throw new InvalidProcessDefinitionException(
                    "Step " + node.step().key() + " has no target");
        }
    }

    private Set<ProcessNode<P, S>> reachableNodes() {
        Set<ProcessNode<P, S>> visited = java.util.Collections.newSetFromMap(
                new IdentityHashMap<>());
        ArrayDeque<ProcessNode<P, S>> queue = new ArrayDeque<>();
        queue.add(start.target());
        while (!queue.isEmpty()) {
            ProcessNode<P, S> node = queue.removeFirst();
            if (!visited.add(node)) {
                continue;
            }
            queue.addAll(targets(node));
        }
        return visited;
    }

    private void validateParallelBranches() {
        for (ProcessNode<P, S> node : nodes.values()) {
            if (!(node instanceof ParallelForkNode<?, ?, ?> rawFork)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            ParallelForkNode<P, S, ?> fork = (ParallelForkNode<P, S, ?>) rawFork;
            ParallelJoinNode<P, S, ?> join = fork.join();
            for (Map.Entry<?, ? extends ProcessNode<P, S>> branch
                    : typedBranchEntries(fork).entrySet()) {
                if (!allPathsReach(
                        branch.getValue(),
                        join,
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>()),
                        new IdentityHashMap<>())) {
                    throw new InvalidProcessDefinitionException(
                            "Parallel branch " + branch.getKey()
                                    + " from " + fork.step().key()
                                    + " does not reach paired join " + join.step().key());
                }
            }
        }
    }

    private boolean allPathsReach(
            ProcessNode<P, S> node,
            ProcessNode<P, S> join,
            Set<ProcessNode<P, S>> visiting,
            Map<ProcessNode<P, S>, Boolean> memo) {
        if (node == join) {
            return true;
        }
        Boolean known = memo.get(node);
        if (known != null) {
            return known;
        }
        if (!visiting.add(node)) {
            return false;
        }
        List<ProcessNode<P, S>> next = targets(node);
        boolean reaches = !next.isEmpty();
        for (ProcessNode<P, S> target : next) {
            if (!allPathsReach(target, join, visiting, memo)) {
                reaches = false;
                break;
            }
        }
        visiting.remove(node);
        memo.put(node, reaches);
        return reaches;
    }

    private void validateNoImmediateCycles() {
        Map<ProcessNode<P, S>, VisitState> states = new IdentityHashMap<>();
        for (ProcessNode<P, S> node : nodes.values()) {
            if (isImmediate(node) && detectImmediateCycle(node, states)) {
                throw new InvalidProcessDefinitionException(
                        "Immediate-only cycle includes step " + node.step().key());
            }
        }
    }

    private boolean detectImmediateCycle(
            ProcessNode<P, S> node,
            Map<ProcessNode<P, S>, VisitState> states) {
        VisitState state = states.get(node);
        if (state == VisitState.VISITING) {
            return true;
        }
        if (state == VisitState.VISITED) {
            return false;
        }
        states.put(node, VisitState.VISITING);
        for (ProcessNode<P, S> target : targets(node)) {
            if (isImmediate(target) && detectImmediateCycle(target, states)) {
                return true;
            }
        }
        states.put(node, VisitState.VISITED);
        return false;
    }

    private static boolean isImmediate(ProcessNode<?, ?> node) {
        return switch (node.type()) {
            case AUTOMATIC, DECISION, PARALLEL_FORK, PARALLEL_JOIN -> true;
            default -> false;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ProcessNode<P, S>> targets(ProcessNode<P, S> node) {
        return switch (node.type()) {
            case HUMAN_TASK ->
                    List.copyOf(((HumanTaskNode) node).transitions().values());
            case AUTOMATIC -> List.of(((AutomaticNode<P, S>) node).target());
            case DECISION ->
                    List.copyOf(((DecisionNode) node).transitions().values());
            case TIMER -> List.of(((TimerNode<P, S>) node).target());
            case PARALLEL_FORK ->
                    List.copyOf(((ParallelForkNode) node).branches().values());
            case PARALLEL_JOIN -> List.of(((ParallelJoinNode) node).target());
            case SUBPROCESS -> {
                SubprocessNode subprocess = (SubprocessNode) node;
                List<ProcessNode<P, S>> result =
                        new ArrayList<>(subprocess.terminalRoutes().values());
                result.add(subprocess.cancellationTarget());
                yield List.copyOf(result);
            }
            case START -> List.of(start.target());
            case END -> List.of();
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<?, ProcessNode<P, S>> typedBranchEntries(ParallelForkNode<P, S, ?> fork) {
        return (Map) fork.branches();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ProcessNodeDescriptor describe(ProcessNode<P, S> node) {
        Map<String, String> routes = new LinkedHashMap<>();
        switch (node.type()) {
            case HUMAN_TASK -> ((HumanTaskNode) node).transitions().forEach(
                    (route, target) -> routes.put(
                            ((Enum<?>) route).name(), ((ProcessNode<?, ?>) target).step().key()));
            case AUTOMATIC ->
                    routes.put("next", ((AutomaticNode<?, ?>) node).target().step().key());
            case DECISION -> ((DecisionNode) node).transitions().forEach(
                    (route, target) -> routes.put(
                            ((Enum<?>) route).name(), ((ProcessNode<?, ?>) target).step().key()));
            case TIMER -> routes.put("next", ((TimerNode<?, ?>) node).target().step().key());
            case PARALLEL_FORK -> ((ParallelForkNode) node).branches().forEach(
                    (route, target) -> routes.put(
                            ((Enum<?>) route).name(), ((ProcessNode<?, ?>) target).step().key()));
            case PARALLEL_JOIN ->
                    routes.put("next", ((ParallelJoinNode<?, ?, ?>) node).target().step().key());
            case SUBPROCESS -> {
                SubprocessNode subprocess = (SubprocessNode) node;
                subprocess.terminalRoutes().forEach(
                        (route, target) -> routes.put(
                                "completed:" + ((ProcessStepKey) route).key(),
                                ((ProcessNode<?, ?>) target).step().key()));
                routes.put(
                        "cancelled",
                        ((ProcessNode<?, ?>) subprocess.cancellationTarget()).step().key());
            }
            case START, END -> {
            }
        }
        return new ProcessNodeDescriptor(node.step().key(), node.type(), routes);
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

    private enum VisitState {
        VISITING,
        VISITED
    }
}
