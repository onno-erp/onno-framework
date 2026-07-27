package su.onno.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import su.onno.metadata.MetadataRegistry;
import su.onno.schema.SchemaGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExtendedProcessEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final ProcessActor WORKER =
            new ProcessActor("worker", Set.of("WORKER"));

    @Test
    void executesAutomaticStepsAndTypedDecisionsInTheStartingTransaction() throws Exception {
        FlowDefinition definition = new FlowDefinition();
        Harness harness = harness(
                new MutableClock(NOW), List.of(definition), List.of());

        ProcessSnapshot completed = harness.engine.start(
                definition, new FlowPayload(4, List.of()), WORKER);

        assertThat(completed.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(completed.currentStep()).isEqualTo(FlowStep.POSITIVE.key());
        assertThat(harness.engine.history(completed.id()))
                .extracting(ProcessTransitionSnapshot::type)
                .containsExactly(
                        ProcessTransitionType.START,
                        ProcessTransitionType.AUTOMATIC,
                        ProcessTransitionType.DECISION);
        assertThat(harness.payload(completed.id(), FlowPayload.class))
                .isEqualTo(new FlowPayload(5, List.of("normalized")));
    }

    @Test
    void resumesDurableTimersOnlyAfterTheirDueTime() {
        MutableClock clock = new MutableClock(NOW);
        TimerDefinition definition = new TimerDefinition();
        Harness harness = harness(clock, List.of(definition), List.of());

        ProcessSnapshot waiting = harness.engine.start(definition, "payload", WORKER);

        assertThat(waiting.status()).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(waiting.activeSteps()).containsExactly(TimerStep.WAIT.key());
        assertThat(harness.engine.runPending(10)).isZero();

        clock.advance(Duration.ofHours(1));

        assertThat(harness.engine.runPending(10)).isEqualTo(1);
        assertThat(harness.engine.find(waiting.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.COMPLETED);
        assertThat(harness.engine.history(waiting.id()))
                .extracting(ProcessTransitionSnapshot::type)
                .containsExactly(ProcessTransitionType.START, ProcessTransitionType.TIMER);
    }

    @Test
    void waitsForEveryTypedParallelBranchBeforeJoining() {
        ParallelDefinition definition = new ParallelDefinition();
        Harness harness = harness(
                new MutableClock(NOW), List.of(definition), List.of());

        ProcessSnapshot started = harness.engine.start(definition, "payload", WORKER);
        List<ProcessWorkItem> tasks = harness.engine.inbox(WORKER);

        assertThat(started.currentStep()).isNull();
        assertThat(started.activeSteps())
                .containsExactlyInAnyOrder(
                        ParallelStep.LEFT_REVIEW.key(),
                        ParallelStep.RIGHT_REVIEW.key());
        assertThat(tasks).hasSize(2);

        harness.engine.complete(tasks.getFirst().id(), Done.DONE, WORKER);
        ProcessSnapshot halfway = harness.engine.find(started.id()).orElseThrow();

        assertThat(halfway.status()).isEqualTo(ProcessStatus.ACTIVE);
        assertThat(halfway.activeSteps()).contains(ParallelStep.JOIN.key());
        assertThat(halfway.activeSteps()).filteredOn(
                step -> step.equals(ParallelStep.LEFT_REVIEW.key())
                        || step.equals(ParallelStep.RIGHT_REVIEW.key()))
                .hasSize(1);

        harness.engine.complete(tasks.getLast().id(), Done.DONE, WORKER);

        assertThat(harness.engine.find(started.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.COMPLETED);
        assertThat(harness.engine.history(started.id()))
                .extracting(ProcessTransitionSnapshot::type)
                .contains(ProcessTransitionType.FORK, ProcessTransitionType.JOIN);
    }

    @Test
    void resumesAParentAfterAChildCompletesAndCancelsChildrenWithTheirParent() {
        ChildDefinition child = new ChildDefinition();
        ParentDefinition parent = new ParentDefinition(child);
        Harness harness = harness(
                new MutableClock(NOW), List.of(child, parent), List.of());

        ProcessSnapshot started = harness.engine.start(
                parent, new ParentPayload(false), WORKER);
        ProcessSnapshot childInstance = harness.engine.instances(WORKER).stream()
                .filter(instance -> started.id().equals(instance.parentInstanceId()))
                .findFirst()
                .orElseThrow();
        ProcessWorkItem childTask = harness.engine.inbox(WORKER).getFirst();

        harness.engine.complete(childTask.id(), Done.DONE, WORKER);

        assertThat(harness.engine.find(childInstance.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.COMPLETED);
        assertThat(harness.engine.find(started.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.COMPLETED);
        assertThat(harness.payload(started.id(), ParentPayload.class).childCompleted())
                .isTrue();

        ProcessSnapshot second = harness.engine.start(
                parent, new ParentPayload(false), WORKER);
        ProcessSnapshot secondChild = harness.engine.instances(WORKER).stream()
                .filter(instance -> second.id().equals(instance.parentInstanceId()))
                .findFirst()
                .orElseThrow();
        ProcessWorkItem cancelledTask = harness.engine.inbox(WORKER).getFirst();

        ProcessSnapshot cancelled = harness.engine.cancel(
                second.id(), "No longer required", WORKER);

        assertThat(cancelled.status()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(harness.engine.find(secondChild.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.CANCELLED);
        assertThat(harness.engine.inbox(WORKER)).isEmpty();
        assertThat(harness.engine.workItemHistory(cancelledTask.id(), WORKER))
                .extracting(ProcessWorkItemEventSnapshot::type)
                .containsExactly(WorkItemEventType.CREATED, WorkItemEventType.CANCELLED);
    }

    @Test
    void migratesPayloadAndActiveTokensToTheLatestDefinitionVersion() {
        MutableClock clock = new MutableClock(NOW);
        VersionOne v1 = new VersionOne();
        Harness harness = harness(clock, List.of(v1), List.of());
        ProcessSnapshot started = harness.engine.start(
                v1, new VersionPayload("request", 1), WORKER);
        UUID oldTaskId = harness.engine.inbox(WORKER).getFirst().id();

        VersionTwo v2 = new VersionTwo();
        ProcessDefinitionMigration<
                VersionPayload, VersionOneStep,
                VersionPayload, VersionTwoStep> migration =
                new ProcessDefinitionMigration<>() {
                    @Override
                    public ProcessDefinition<VersionPayload, VersionOneStep> from() {
                        return v1;
                    }

                    @Override
                    public ProcessDefinition<VersionPayload, VersionTwoStep> to() {
                        return v2;
                    }

                    @Override
                    public ProcessMigrationResult<VersionPayload, VersionTwoStep> migrate(
                            ProcessMigrationState<VersionPayload, VersionOneStep> state) {
                        Map<UUID, VersionTwoStep> tokenSteps = new LinkedHashMap<>();
                        state.tokens().forEach(token ->
                                tokenSteps.put(token.tokenId(), VersionTwoStep.REVIEW));
                        return new ProcessMigrationResult<>(
                                new VersionPayload(state.payload().name(), 2), tokenSteps);
                    }
                };
        harness.reopen(List.of(v1, v2), List.of(migration));

        ProcessSnapshot migrated = harness.engine.migrate(started.id(), WORKER);

        assertThat(migrated.definitionVersion()).isEqualTo(2);
        assertThat(migrated.activeSteps()).containsExactly(VersionTwoStep.REVIEW.key());
        assertThat(harness.payload(started.id(), VersionPayload.class).revision()).isEqualTo(2);
        assertThat(harness.engine.workItemHistory(oldTaskId, WORKER))
                .extracting(ProcessWorkItemEventSnapshot::type)
                .containsExactly(WorkItemEventType.CREATED, WorkItemEventType.CANCELLED);
        ProcessWorkItem replacement = harness.engine.inbox(WORKER).getFirst();
        assertThat(replacement.definitionVersion()).isEqualTo(2);

        harness.engine.complete(replacement.id(), Done.DONE, WORKER);

        assertThat(harness.engine.find(started.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.COMPLETED);
        assertThat(harness.engine.history(started.id()))
                .extracting(ProcessTransitionSnapshot::type)
                .contains(ProcessTransitionType.MIGRATION);
    }

    @Test
    void migratesParallelTokensWithoutDuplicatingActiveBranches() {
        MutableClock clock = new MutableClock(NOW);
        ParallelVersionOne v1 = new ParallelVersionOne();
        Harness harness = harness(clock, List.of(v1), List.of());
        ProcessSnapshot started = harness.engine.start(v1, "payload", WORKER);

        ParallelVersionTwo v2 = new ParallelVersionTwo();
        ProcessDefinitionMigration<
                String, ParallelMigrationStep,
                String, ParallelMigrationStep> migration =
                new ProcessDefinitionMigration<>() {
                    @Override
                    public ProcessDefinition<String, ParallelMigrationStep> from() {
                        return v1;
                    }

                    @Override
                    public ProcessDefinition<String, ParallelMigrationStep> to() {
                        return v2;
                    }

                    @Override
                    public ProcessMigrationResult<String, ParallelMigrationStep> migrate(
                            ProcessMigrationState<String, ParallelMigrationStep> state) {
                        Map<UUID, ParallelMigrationStep> targets = new LinkedHashMap<>();
                        state.tokens().forEach(token -> targets.put(
                                token.tokenId(),
                                token.parentTokenId() == null
                                        ? ParallelMigrationStep.FORK
                                        : Branch.LEFT.name().equals(token.branchKey())
                                                ? ParallelMigrationStep.LEFT_V2
                                                : ParallelMigrationStep.RIGHT_V2));
                        return new ProcessMigrationResult<>(state.payload(), targets);
                    }
                };
        harness.reopen(List.of(v1, v2), List.of(migration));

        ProcessSnapshot migrated = harness.engine.migrate(started.id(), WORKER);

        assertThat(migrated.activeSteps()).containsExactlyInAnyOrder(
                ParallelMigrationStep.LEFT_V2.key(),
                ParallelMigrationStep.RIGHT_V2.key());
        assertThat(harness.engine.tokens(started.id())).hasSize(3);
        List<ProcessWorkItem> replacementTasks = harness.engine.inbox(WORKER);
        assertThat(replacementTasks).hasSize(2);

        replacementTasks.forEach(task ->
                harness.engine.complete(task.id(), Done.DONE, WORKER));

        assertThat(harness.engine.find(started.id()).orElseThrow().status())
                .isEqualTo(ProcessStatus.COMPLETED);
    }

    private static Harness harness(
            MutableClock clock,
            List<ProcessDefinition<?, ?>> definitions,
            List<ProcessDefinitionMigration<?, ?, ?, ?>> migrations
    ) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:extended-process-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(dataSource);
        new SchemaGenerator(new MetadataRegistry()).execute(jdbi);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Harness harness = new Harness(jdbi, mapper, clock);
        harness.reopen(definitions, migrations);
        return harness;
    }

    private static final class Harness {
        private final Jdbi jdbi;
        private final ObjectMapper mapper;
        private final MutableClock clock;
        private JdbcProcessEngine engine;

        private Harness(Jdbi jdbi, ObjectMapper mapper, MutableClock clock) {
            this.jdbi = jdbi;
            this.mapper = mapper;
            this.clock = clock;
        }

        private void reopen(
                List<ProcessDefinition<?, ?>> definitions,
                List<ProcessDefinitionMigration<?, ?, ?, ?>> migrations
        ) {
            engine = new JdbcProcessEngine(
                    jdbi, new ProcessDefinitions(definitions, migrations),
                    new MetadataRegistry(), new JacksonProcessPayloadCodec(mapper),
                    clock, ignored -> { });
        }

        private <T> T payload(UUID instanceId, Class<T> type) {
            String json = jdbi.withHandle(handle -> handle.createQuery("""
                            select _payload from onno_process_instances where _id = :id
                            """)
                    .bind("id", instanceId)
                    .mapTo(String.class)
                    .one());
            try {
                return mapper.readValue(json, type);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        private void advance(Duration duration) {
            value = value.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }

    private record FlowPayload(int value, List<String> trace) {
        private FlowPayload {
            trace = trace == null ? List.of() : List.copyOf(trace);
        }
    }

    private enum FlowStep implements ProcessStepKey {
        NORMALIZE, ROUTE, POSITIVE, NEGATIVE
    }

    private enum Route {
        POSITIVE, NEGATIVE
    }

    private static final class FlowDefinition
            extends ProcessDefinition<FlowPayload, FlowStep> {
        private FlowDefinition() {
            super("extended-flow", FlowPayload.class);
        }

        @Override
        public TaskAssignment startAssignment(FlowPayload payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<FlowPayload, FlowStep> graph) {
            var normalize = graph.automatic(FlowStep.NORMALIZE, (payload, context) -> {
                List<String> trace = new ArrayList<>(payload.trace());
                trace.add("normalized");
                return new FlowPayload(payload.value() + 1, trace);
            });
            var route = graph.decision(
                    FlowStep.ROUTE, new TypedDecision<FlowPayload, Route>() {
                @Override
                public Class<Route> outcomeType() {
                    return Route.class;
                }

                @Override
                public Route decide(FlowPayload payload) {
                    return payload.value() > 0 ? Route.POSITIVE : Route.NEGATIVE;
                }
            });
            var positive = graph.end(FlowStep.POSITIVE);
            var negative = graph.end(FlowStep.NEGATIVE);
            graph.start().to(normalize);
            normalize.to(route);
            route.on(Route.POSITIVE).to(positive);
            route.on(Route.NEGATIVE).to(negative);
        }
    }

    private enum TimerStep implements ProcessStepKey {
        WAIT, DONE
    }

    private static final class TimerDefinition
            extends ProcessDefinition<String, TimerStep> {
        private TimerDefinition() {
            super("extended-timer", String.class);
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<String, TimerStep> graph) {
            var timer = graph.timer(TimerStep.WAIT, ProcessTimer.after(Duration.ofHours(1)));
            var done = graph.end(TimerStep.DONE);
            graph.start().to(timer);
            timer.to(done);
        }
    }

    private enum Done {
        DONE
    }

    private static final class WorkerTask<P> implements HumanTask<P, Done> {
        private final String title;

        private WorkerTask(String title) {
            this.title = title;
        }

        @Override
        public Class<Done> outcomeType() {
            return Done.class;
        }

        @Override
        public String title(P payload) {
            return title;
        }

        @Override
        public TaskAssignment assignment(P payload) {
            return TaskAssignment.roles("WORKER");
        }
    }

    private enum Branch {
        LEFT, RIGHT
    }

    private enum ParallelStep implements ProcessStepKey {
        FORK, LEFT_REVIEW, RIGHT_REVIEW, JOIN, DONE
    }

    private static final class ParallelDefinition
            extends ProcessDefinition<String, ParallelStep> {
        private ParallelDefinition() {
            super("extended-parallel", String.class);
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<String, ParallelStep> graph) {
            var fork = graph.parallel(
                    ParallelStep.FORK, Branch.class, ParallelStep.JOIN);
            var left = graph.human(
                    ParallelStep.LEFT_REVIEW, new WorkerTask<String>("Left review"));
            var right = graph.human(
                    ParallelStep.RIGHT_REVIEW, new WorkerTask<String>("Right review"));
            var done = graph.end(ParallelStep.DONE);
            graph.start().to(fork);
            fork.on(Branch.LEFT).to(left);
            fork.on(Branch.RIGHT).to(right);
            left.on(Done.DONE).to(fork.join());
            right.on(Done.DONE).to(fork.join());
            fork.join().to(done);
        }
    }

    private record ChildPayload(boolean approved) {
    }

    private enum ChildStep implements ProcessStepKey {
        REVIEW, DONE
    }

    private static final class ChildDefinition
            extends ProcessDefinition<ChildPayload, ChildStep> {
        private ChildDefinition() {
            super("extended-child", ChildPayload.class);
        }

        @Override
        public TaskAssignment startAssignment(ChildPayload payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<ChildPayload, ChildStep> graph) {
            var review = graph.human(
                    ChildStep.REVIEW, new WorkerTask<ChildPayload>("Child review"));
            var done = graph.end(ChildStep.DONE);
            graph.start().to(review);
            review.on(Done.DONE).to(done);
        }
    }

    private record ParentPayload(boolean childCompleted) {
    }

    private enum ParentStep implements ProcessStepKey {
        CHILD, DONE, CHILD_CANCELLED
    }

    private static final class ParentDefinition
            extends ProcessDefinition<ParentPayload, ParentStep> {
        private final ChildDefinition child;

        private ParentDefinition(ChildDefinition child) {
            super("extended-parent", ParentPayload.class);
            this.child = child;
        }

        @Override
        public TaskAssignment startAssignment(ParentPayload payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<ParentPayload, ParentStep> graph) {
            var subprocess = graph.subprocess(
                    ParentStep.CHILD,
                    new SubprocessCall<
                            ParentPayload, ChildPayload, ChildStep>() {
                        @Override
                        public ProcessDefinition<ChildPayload, ChildStep> definition() {
                            return child;
                        }

                        @Override
                        public ChildPayload payload(ParentPayload parentPayload) {
                            return new ChildPayload(false);
                        }

                        @Override
                        public ParentPayload merge(
                                ParentPayload parentPayload,
                                ChildPayload completedChildPayload
                        ) {
                            return new ParentPayload(true);
                        }
                    });
            var done = graph.end(ParentStep.DONE);
            var cancelled = graph.end(ParentStep.CHILD_CANCELLED);
            graph.start().to(subprocess);
            subprocess.on(ChildStep.DONE).to(done);
            subprocess.onCancellation().to(cancelled);
        }
    }

    private record VersionPayload(String name, int revision) {
    }

    private enum VersionOneStep implements ProcessStepKey {
        APPROVAL, DONE
    }

    private static final class VersionOne
            extends ProcessDefinition<VersionPayload, VersionOneStep> {
        private VersionOne() {
            super("extended-versioned", 1, VersionPayload.class);
        }

        @Override
        public TaskAssignment startAssignment(VersionPayload payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<VersionPayload, VersionOneStep> graph) {
            var approval = graph.human(
                    VersionOneStep.APPROVAL,
                    new WorkerTask<VersionPayload>("Legacy approval"));
            var done = graph.end(VersionOneStep.DONE);
            graph.start().to(approval);
            approval.on(Done.DONE).to(done);
        }
    }

    private enum VersionTwoStep implements ProcessStepKey {
        REVIEW, DONE
    }

    private static final class VersionTwo
            extends ProcessDefinition<VersionPayload, VersionTwoStep> {
        private VersionTwo() {
            super("extended-versioned", 2, VersionPayload.class);
        }

        @Override
        public TaskAssignment startAssignment(VersionPayload payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<VersionPayload, VersionTwoStep> graph) {
            var review = graph.human(
                    VersionTwoStep.REVIEW,
                    new WorkerTask<VersionPayload>("Version two review"));
            var done = graph.end(VersionTwoStep.DONE);
            graph.start().to(review);
            review.on(Done.DONE).to(done);
        }
    }

    private enum ParallelMigrationStep implements ProcessStepKey {
        FORK, LEFT_V1, RIGHT_V1, LEFT_V2, RIGHT_V2, JOIN, DONE
    }

    private static final class ParallelVersionOne
            extends ProcessDefinition<String, ParallelMigrationStep> {
        private ParallelVersionOne() {
            super("extended-parallel-versioned", 1, String.class);
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<String, ParallelMigrationStep> graph) {
            var fork = graph.parallel(
                    ParallelMigrationStep.FORK,
                    Branch.class,
                    ParallelMigrationStep.JOIN);
            var left = graph.human(
                    ParallelMigrationStep.LEFT_V1,
                    new WorkerTask<String>("Legacy left review"));
            var right = graph.human(
                    ParallelMigrationStep.RIGHT_V1,
                    new WorkerTask<String>("Legacy right review"));
            var done = graph.end(ParallelMigrationStep.DONE);
            graph.start().to(fork);
            fork.on(Branch.LEFT).to(left);
            fork.on(Branch.RIGHT).to(right);
            left.on(Done.DONE).to(fork.join());
            right.on(Done.DONE).to(fork.join());
            fork.join().to(done);
        }
    }

    private static final class ParallelVersionTwo
            extends ProcessDefinition<String, ParallelMigrationStep> {
        private ParallelVersionTwo() {
            super("extended-parallel-versioned", 2, String.class);
        }

        @Override
        public TaskAssignment startAssignment(String payload) {
            return TaskAssignment.roles("WORKER");
        }

        @Override
        protected void define(ProcessGraph<String, ParallelMigrationStep> graph) {
            var fork = graph.parallel(
                    ParallelMigrationStep.FORK,
                    Branch.class,
                    ParallelMigrationStep.JOIN);
            var left = graph.human(
                    ParallelMigrationStep.LEFT_V2,
                    new WorkerTask<String>("New left review"));
            var right = graph.human(
                    ParallelMigrationStep.RIGHT_V2,
                    new WorkerTask<String>("New right review"));
            var done = graph.end(ParallelMigrationStep.DONE);
            graph.start().to(fork);
            fork.on(Branch.LEFT).to(left);
            fork.on(Branch.RIGHT).to(right);
            left.on(Done.DONE).to(fork.join());
            right.on(Done.DONE).to(fork.join());
            fork.join().to(done);
        }
    }
}
