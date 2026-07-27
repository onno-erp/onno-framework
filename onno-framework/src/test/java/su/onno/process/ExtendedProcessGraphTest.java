package su.onno.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtendedProcessGraphTest {

    @Test
    void describesACompleteGraphWithImmediateWaitParallelAndSubprocessNodes() {
        ChildDefinition child = new ChildDefinition();
        CompleteDefinition definition = new CompleteDefinition(child);

        ProcessGraphDescriptor descriptor = definition.graph().descriptor();

        assertThat(definition.version()).isEqualTo(1);
        assertThat(descriptor.startStepKey()).isEqualTo(ParentStep.TIMER.key());
        assertThat(descriptor.nodes())
                .extracting(ProcessNodeDescriptor::type)
                .containsExactly(
                        ProcessNodeType.TIMER,
                        ProcessNodeType.AUTOMATIC,
                        ProcessNodeType.DECISION,
                        ProcessNodeType.PARALLEL_FORK,
                        ProcessNodeType.PARALLEL_JOIN,
                        ProcessNodeType.SUBPROCESS,
                        ProcessNodeType.END,
                        ProcessNodeType.END);
        ProcessNodeDescriptor subprocess = descriptor.nodes().stream()
                .filter(node -> node.type() == ProcessNodeType.SUBPROCESS)
                .findFirst()
                .orElseThrow();
        assertThat(subprocess.routes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "completed:SUCCESS", ParentStep.COMPLETED.key(),
                "completed:FAILED", ParentStep.CANCELLED.key(),
                "cancelled", ParentStep.CANCELLED.key()));
    }

    @Test
    void rejectsMissingSingleTargetsAndIncompleteDecisionRoutes() {
        ProcessDefinition<String, ValidationStep> missingTarget =
                definition("missing-target", graph -> {
                    var automatic =
                            graph.automatic(ValidationStep.A, (payload, context) -> payload);
                    graph.start().to(automatic);
                });

        assertThatThrownBy(missingTarget::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("A")
                .hasMessageContaining("no target");

        ProcessDefinition<String, ValidationStep> incompleteDecision =
                definition("incomplete-decision", graph -> {
                    var decision = graph.decision(ValidationStep.A, new YesNoDecision());
                    var end = graph.end(ValidationStep.END);
                    graph.start().to(decision);
                    decision.on(YesNo.YES).to(end);
                });

        assertThatThrownBy(incompleteDecision::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("NO");
    }

    @Test
    void rejectsParallelBranchesThatCanBypassTheirPairedJoin() {
        ProcessDefinition<String, ParallelStep> invalid =
                new ProcessDefinition<>("parallel-bypass", String.class) {
                    @Override
                    public TaskAssignment startAssignment(String payload) {
                        return TaskAssignment.roles("USER");
                    }

                    @Override
                    protected void define(ProcessGraph<String, ParallelStep> graph) {
                        var fork = graph.parallel(
                                ParallelStep.FORK, Branch.class, ParallelStep.JOIN);
                        var end = graph.end(ParallelStep.END);
                        graph.start().to(fork);
                        fork.on(Branch.LEFT).to(fork.join());
                        fork.on(Branch.RIGHT).to(end);
                        fork.join().to(end);
                    }
                };

        assertThatThrownBy(invalid::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("RIGHT")
                .hasMessageContaining("JOIN");
    }

    @Test
    void rejectsCyclesMadeOnlyOfImmediateNodes() {
        ProcessDefinition<String, ValidationStep> cyclic =
                definition("immediate-cycle", graph -> {
                    var first = graph.automatic(
                            ValidationStep.A, (payload, context) -> payload);
                    var second = graph.automatic(
                            ValidationStep.B, (payload, context) -> payload);
                    graph.start().to(first);
                    first.to(second);
                    second.to(first);
                });

        assertThatThrownBy(cyclic::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("Immediate-only cycle");
    }

    @Test
    void validatesSubprocessTerminalAndCancellationRoutes() {
        ChildDefinition child = new ChildDefinition();
        ProcessDefinition<String, ValidationStep> incomplete =
                definition("incomplete-child-routes", graph -> {
                    var subprocess = graph.subprocess(
                            ValidationStep.A, new StringChildCall(child));
                    var end = graph.end(ValidationStep.END);
                    graph.start().to(subprocess);
                    subprocess.on(ChildStep.SUCCESS).to(end);
                    subprocess.onCancellation().to(end);
                });

        assertThatThrownBy(incomplete::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void registersVersionsAndReturnsATypedMigrationPath() {
        VersionedDefinition v1 = new VersionedDefinition(1);
        VersionedDefinition v2 = new VersionedDefinition(2);
        ProcessDefinitionMigration<String, VersionStep, String, VersionStep> migration =
                new ProcessDefinitionMigration<>() {
                    @Override
                    public ProcessDefinition<String, VersionStep> from() {
                        return v1;
                    }

                    @Override
                    public ProcessDefinition<String, VersionStep> to() {
                        return v2;
                    }

                    @Override
                    public ProcessMigrationResult<String, VersionStep> migrate(
                            ProcessMigrationState<String, VersionStep> state) {
                        return new ProcessMigrationResult<>(
                                state.payload() + "-v2",
                                Map.of(state.tokens().getFirst().tokenId(), VersionStep.END));
                    }
                };
        ProcessDefinitions definitions =
                new ProcessDefinitions(List.of(v1, v2), List.of(migration));
        UUID tokenId = UUID.randomUUID();

        assertThat(definitions.require("versioned")).isSameAs(v2);
        assertThat(definitions.require("versioned", 1)).isSameAs(v1);
        assertThat(definitions.all()).containsExactly(v2);
        assertThat(definitions.allVersions()).containsExactly(v1, v2);
        assertThat(definitions.migrationPath("versioned", 1)).containsExactly(migration);

        ProcessMigrationResult<String, VersionStep> result = migration.migrate(
                new ProcessMigrationState<>(
                        "payload",
                        List.of(new ProcessMigrationToken<>(
                                tokenId, VersionStep.END, null, null))));
        assertThat(result.payload()).isEqualTo("payload-v2");
        assertThat(result.tokenSteps()).containsEntry(tokenId, VersionStep.END);
    }

    @Test
    void rejectsDuplicateVersionsAndAmbiguousMigrationEdges() {
        VersionedDefinition firstV1 = new VersionedDefinition(1);
        VersionedDefinition secondV1 = new VersionedDefinition(1);
        assertThatThrownBy(() -> new ProcessDefinitions(List.of(firstV1, secondV1)))
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("versioned v1");

        VersionedDefinition v2 = new VersionedDefinition(2);
        ProcessDefinitionMigration<String, VersionStep, String, VersionStep> migration =
                migration(firstV1, v2);
        assertThatThrownBy(() -> new ProcessDefinitions(
                List.of(firstV1, v2), List.of(migration, migration)))
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("Multiple");
    }

    @Test
    void fingerprintsSemanticGraphStructureWithRoutesSorted() {
        ProcessDefinition<String, ValidationStep> first =
                branchingDefinition("fingerprint", true);
        ProcessDefinition<String, ValidationStep> sameRoutesDifferentOrder =
                branchingDefinition("fingerprint", false);
        ProcessDefinition<String, ValidationStep> changed =
                new ProcessDefinition<>("fingerprint", String.class) {
                    @Override
                    public TaskAssignment startAssignment(String payload) {
                        return TaskAssignment.roles("USER");
                    }

                    @Override
                    protected void define(ProcessGraph<String, ValidationStep> graph) {
                        var decision = graph.decision(ValidationStep.A, new YesNoDecision());
                        var firstEnd = graph.end(ValidationStep.B);
                        var secondEnd = graph.end(ValidationStep.END);
                        graph.start().to(decision);
                        decision.on(YesNo.YES).to(secondEnd);
                        decision.on(YesNo.NO).to(firstEnd);
                    }
                };

        assertThat(first.fingerprint())
                .hasSize(64)
                .isEqualTo(sameRoutesDifferentOrder.fingerprint())
                .isNotEqualTo(changed.fingerprint());
    }

    @Test
    void rejectsDirectAndTransitiveSubprocessDefinitionCycles() {
        @SuppressWarnings("unchecked")
        ProcessDefinition<String, ValidationStep>[] direct = new ProcessDefinition[1];
        direct[0] = cyclicDefinition("direct-cycle", () -> direct[0]);

        assertThatThrownBy(direct[0]::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("Subprocess definition cycle")
                .hasMessageContaining("direct-cycle v1 -> direct-cycle v1");

        @SuppressWarnings("unchecked")
        ProcessDefinition<String, ValidationStep>[] transitive = new ProcessDefinition[2];
        transitive[0] = cyclicDefinition("cycle-a", () -> transitive[1]);
        transitive[1] = cyclicDefinition("cycle-b", () -> transitive[0]);

        assertThatThrownBy(() -> new ProcessDefinitions(List.of(transitive)))
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("cycle-a v1 -> cycle-b v1 -> cycle-a v1");
    }

    private static ProcessDefinition<String, ValidationStep> branchingDefinition(
            String key, boolean yesFirst) {
        return new ProcessDefinition<>(key, String.class) {
            @Override
            public TaskAssignment startAssignment(String payload) {
                return TaskAssignment.roles("USER");
            }

            @Override
            protected void define(ProcessGraph<String, ValidationStep> graph) {
                var decision = graph.decision(ValidationStep.A, new YesNoDecision());
                var firstEnd = graph.end(ValidationStep.B);
                var secondEnd = graph.end(ValidationStep.END);
                graph.start().to(decision);
                if (yesFirst) {
                    decision.on(YesNo.YES).to(firstEnd);
                    decision.on(YesNo.NO).to(secondEnd);
                } else {
                    decision.on(YesNo.NO).to(secondEnd);
                    decision.on(YesNo.YES).to(firstEnd);
                }
            }
        };
    }

    private static ProcessDefinition<String, ValidationStep> cyclicDefinition(
            String key,
            java.util.function.Supplier<ProcessDefinition<String, ValidationStep>> child) {
        return new ProcessDefinition<>(key, String.class) {
            @Override
            public TaskAssignment startAssignment(String payload) {
                return TaskAssignment.roles("USER");
            }

            @Override
            protected void define(ProcessGraph<String, ValidationStep> graph) {
                var subprocess = graph.subprocess(
                        ValidationStep.A,
                        new SubprocessCall<String, String, ValidationStep>() {
                            @Override
                            public ProcessDefinition<String, ValidationStep> definition() {
                                return child.get();
                            }

                            @Override
                            public String payload(String parentPayload) {
                                return parentPayload;
                            }
                        });
                var end = graph.end(ValidationStep.END);
                graph.start().to(subprocess);
                subprocess.on(ValidationStep.END).to(end);
                subprocess.onCancellation().to(end);
            }
        };
    }

    private static ProcessDefinitionMigration<String, VersionStep, String, VersionStep> migration(
            VersionedDefinition from, VersionedDefinition to) {
        return new ProcessDefinitionMigration<>() {
            @Override
            public ProcessDefinition<String, VersionStep> from() {
                return from;
            }

            @Override
            public ProcessDefinition<String, VersionStep> to() {
                return to;
            }

            @Override
            public ProcessMigrationResult<String, VersionStep> migrate(
                    ProcessMigrationState<String, VersionStep> state) {
                return new ProcessMigrationResult<>(
                        state.payload(), Map.<UUID, VersionStep>of());
            }
        };
    }

    private static ProcessDefinition<String, ValidationStep> definition(
            String key,
            java.util.function.Consumer<ProcessGraph<String, ValidationStep>> declaration) {
        return new ProcessDefinition<>(key, String.class) {
            @Override
            public TaskAssignment startAssignment(String payload) {
                return TaskAssignment.roles("USER");
            }

            @Override
            protected void define(ProcessGraph<String, ValidationStep> graph) {
                declaration.accept(graph);
            }
        };
    }

    private enum ParentStep implements ProcessStepKey {
        TIMER,
        AUTOMATIC,
        DECISION,
        FORK,
        JOIN,
        SUBPROCESS,
        COMPLETED,
        CANCELLED
    }

    private enum ChildStep implements ProcessStepKey {
        DECISION,
        SUCCESS,
        FAILED
    }

    private enum ValidationStep implements ProcessStepKey {
        A,
        B,
        END
    }

    private enum ParallelStep implements ProcessStepKey {
        FORK,
        JOIN,
        END
    }

    private enum VersionStep implements ProcessStepKey {
        END
    }

    private enum YesNo {
        YES,
        NO
    }

    private enum Branch {
        LEFT,
        RIGHT
    }

    private static final class YesNoDecision implements TypedDecision<String, YesNo> {
        @Override
        public Class<YesNo> outcomeType() {
            return YesNo.class;
        }

        @Override
        public YesNo decide(String payload) {
            return payload.isBlank() ? YesNo.NO : YesNo.YES;
        }
    }

    private static final class ChildDefinition
            extends ProcessDefinition<String, ChildStep> {

        private ChildDefinition() {
            super("child", String.class);
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("USER");
        }

        @Override
        protected void define(ProcessGraph<String, ChildStep> graph) {
            var decision = graph.decision(ChildStep.DECISION, new YesNoDecision());
            var success = graph.end(ChildStep.SUCCESS);
            var failed = graph.end(ChildStep.FAILED);
            graph.start().to(decision);
            decision.on(YesNo.YES).to(success);
            decision.on(YesNo.NO).to(failed);
        }
    }

    private static final class StringChildCall
            implements SubprocessCall<String, String, ChildStep> {

        private final ChildDefinition definition;

        private StringChildCall(ChildDefinition definition) {
            this.definition = definition;
        }

        @Override
        public ProcessDefinition<String, ChildStep> definition() {
            return definition;
        }

        @Override
        public String payload(String parentPayload) {
            return parentPayload;
        }
    }

    private static final class CompleteDefinition
            extends ProcessDefinition<String, ParentStep> {

        private final ChildDefinition child;

        private CompleteDefinition(ChildDefinition child) {
            super("complete", String.class);
            this.child = child;
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("USER");
        }

        @Override
        protected void define(ProcessGraph<String, ParentStep> graph) {
            var timer = graph.timer(
                    ParentStep.TIMER, ProcessTimer.after(Duration.ofMinutes(5)));
            var automatic = graph.automatic(
                    ParentStep.AUTOMATIC, (payload, context) -> payload + "-automatic");
            var decision = graph.decision(ParentStep.DECISION, new YesNoDecision());
            var fork = graph.parallel(ParentStep.FORK, Branch.class, ParentStep.JOIN);
            var subprocess = graph.subprocess(
                    ParentStep.SUBPROCESS, new StringChildCall(child));
            var completed = graph.end(ParentStep.COMPLETED);
            var cancelled = graph.end(ParentStep.CANCELLED);

            graph.start().to(timer);
            timer.to(automatic);
            automatic.to(decision);
            decision.on(YesNo.YES).to(fork);
            decision.on(YesNo.NO).to(cancelled);
            fork.on(Branch.LEFT).to(fork.join());
            fork.on(Branch.RIGHT).to(fork.join());
            fork.join().to(subprocess);
            subprocess.on(ChildStep.SUCCESS).to(completed);
            subprocess.on(ChildStep.FAILED).to(cancelled);
            subprocess.onCancellation().to(cancelled);
        }
    }

    private static final class VersionedDefinition
            extends ProcessDefinition<String, VersionStep> {

        private VersionedDefinition(int version) {
            super("versioned", version, String.class);
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("USER");
        }

        @Override
        protected void define(ProcessGraph<String, VersionStep> graph) {
            graph.start().to(graph.end(VersionStep.END));
        }
    }
}
