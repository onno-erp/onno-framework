package su.onno.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.onno.metadata.MetadataRegistry;
import su.onno.schema.SchemaGenerator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcProcessEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private ApprovalProcess definition;
    private JdbcProcessEngine engine;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:process-" + java.util.UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(dataSource);
        new SchemaGenerator(new MetadataRegistry()).execute(jdbi);
        definition = new ApprovalProcess();
        engine = new JdbcProcessEngine(
                jdbi, new ProcessDefinitions(List.of(definition)),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsClaimsCompletionsAndRoleScopedInbox() {
        ProcessActor manager = new ProcessActor("mara", Set.of("MANAGER"));
        ProcessActor finance = new ProcessActor("finn", Set.of("FINANCE"));
        ProcessSnapshot started = engine.start(
                definition, new Request("PO-42", "Purchase laptops"), manager);

        assertThat(started.currentStep()).isEqualTo("manager-approval");
        ProcessWorkItem managerTask = engine.inbox(manager).getFirst();
        assertThat(engine.inbox(finance)).isEmpty();
        assertThat(managerTask.outcomes()).containsExactlyInAnyOrder("APPROVED", "REJECTED");

        ProcessWorkItem claimed = engine.claim(managerTask.id(), manager);
        assertThat(claimed.status()).isEqualTo(WorkItemStatus.CLAIMED);
        assertThat(claimed.assignee()).isEqualTo("mara");
        assertThatThrownBy(() ->
                engine.complete(managerTask.id(), Outcome.APPROVED, finance))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claimed by");

        ProcessSnapshot awaitingFinance =
                engine.complete(managerTask.id(), Outcome.APPROVED, manager);
        assertThat(awaitingFinance.currentStep()).isEqualTo("finance-approval");
        assertThat(engine.inbox(manager)).isEmpty();

        ProcessWorkItem financeTask = engine.inbox(finance).getFirst();
        ProcessSnapshot completed = engine.complete(financeTask.id(), Outcome.APPROVED, finance);
        assertThat(completed.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(completed.currentStep()).isEqualTo("completed");
        assertThat(engine.inbox(finance)).isEmpty();
        assertThat(engine.find(started.id())).contains(completed);
        assertThat(engine.history(started.id()))
                .extracting(ProcessTransitionSnapshot::toStep)
                .containsExactly("manager-approval", "finance-approval", "completed");
    }

    record Request(String number, String subject) {
    }

    enum Step implements ProcessStepKey {
        MANAGER {
            @Override public String key() { return "manager-approval"; }
        },
        FINANCE {
            @Override public String key() { return "finance-approval"; }
        },
        COMPLETED {
            @Override public String key() { return "completed"; }
        },
        REJECTED {
            @Override public String key() { return "rejected"; }
        }
    }

    enum Outcome { APPROVED, REJECTED }

    static final class RoleTask implements HumanTask<Request, Outcome> {
        private final String title;
        private final String role;

        RoleTask(String title, String role) {
            this.title = title;
            this.role = role;
        }

        @Override public Class<Outcome> outcomeType() { return Outcome.class; }
        @Override public String title(Request payload) { return title + ": " + payload.subject(); }
        @Override public TaskAssignment assignment(Request payload) {
            return TaskAssignment.roles(role);
        }
    }

    static final class ApprovalProcess extends ProcessDefinition<Request, Step> {
        ApprovalProcess() {
            super("purchase-approval", Request.class);
        }

        @Override
        public TaskAssignment startAssignment(Request payload) {
            return TaskAssignment.roles("MANAGER");
        }

        @Override
        protected void define(ProcessGraph<Request, Step> graph) {
            var manager = graph.human(Step.MANAGER, new RoleTask("Manager approval", "MANAGER"));
            var finance = graph.human(Step.FINANCE, new RoleTask("Finance approval", "FINANCE"));
            var completed = graph.end(Step.COMPLETED);
            var rejected = graph.end(Step.REJECTED);
            graph.start().to(manager);
            manager.on(Outcome.APPROVED).to(finance);
            manager.on(Outcome.REJECTED).to(rejected);
            finance.on(Outcome.APPROVED).to(completed);
            finance.on(Outcome.REJECTED).to(rejected);
        }
    }
}
