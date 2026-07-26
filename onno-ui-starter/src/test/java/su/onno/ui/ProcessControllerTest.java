package su.onno.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import su.onno.metadata.MetadataRegistry;
import su.onno.process.HumanTask;
import su.onno.process.JdbcProcessEngine;
import su.onno.process.ProcessDefinition;
import su.onno.process.ProcessDefinitions;
import su.onno.process.ProcessGraph;
import su.onno.process.ProcessStepKey;
import su.onno.process.ProcessStatus;
import su.onno.process.TaskAssignment;
import su.onno.schema.SchemaGenerator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessControllerTest {

    private ProcessController controller;
    private UsernamePasswordAuthenticationToken manager;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:process-api-" + java.util.UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(dataSource);
        new SchemaGenerator(new MetadataRegistry()).execute(jdbi);
        Approval definition = new Approval();
        ProcessDefinitions definitions = new ProcessDefinitions(List.of(definition));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        controller = new ProcessController(
                new JdbcProcessEngine(jdbi, definitions, json, ignored -> { }),
                definitions,
                json,
                new UiAccessService(new MetadataRegistry()));
        manager = new UsernamePasswordAuthenticationToken(
                "mara", "n/a", List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
    }

    @Test
    void startsTypedPayloadAndCompletesAuthenticatedInboxTask() {
        var started = controller.start(
                "approval", Map.of("number", "O-42"), manager);

        var task = controller.inbox(manager).getFirst();
        assertThat(task.title()).isEqualTo("Review O-42");
        assertThat(task.candidateRoles()).containsExactly("MANAGER");

        var claimed = controller.claim(task.id(), manager);
        assertThat(claimed.assignee()).isEqualTo("mara");

        var completed = controller.complete(
                task.id(), new ProcessController.CompleteTaskRequest("APPROVE"), manager);
        assertThat(completed.id()).isEqualTo(started.id());
        assertThat(completed.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(controller.inbox(manager)).isEmpty();
    }

    @Test
    void enforcesTypedStartAndInstanceAccessAssignments() {
        var outsider = new UsernamePasswordAuthenticationToken(
                "olivia", "n/a", List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));

        assertThatThrownBy(() ->
                controller.start("approval", Map.of("number", "O-43"), outsider))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(403));

        var started = controller.start("approval", Map.of("number", "O-44"), manager);
        assertThatThrownBy(() -> controller.get(started.id(), outsider))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void delegatesAndExposesTaskAudit() {
        var delegate = new UsernamePasswordAuthenticationToken("mina", "n/a", List.of());
        controller.start("approval", Map.of("number", "O-45"), manager);
        var task = controller.claim(controller.inbox(manager).getFirst().id(), manager);

        var delegated = controller.delegate(
                task.id(),
                new ProcessController.DelegateTaskRequest("mina", "Manager is away"),
                manager);

        assertThat(delegated.assignee()).isEqualTo("mina");
        assertThat(controller.inbox(delegate)).extracting(su.onno.process.ProcessWorkItem::id)
                .containsExactly(task.id());
        assertThat(controller.workItemHistory(task.id(), delegate))
                .extracting(su.onno.process.ProcessWorkItemEventSnapshot::type)
                .containsExactly(
                        su.onno.process.WorkItemEventType.CREATED,
                        su.onno.process.WorkItemEventType.CLAIMED,
                        su.onno.process.WorkItemEventType.DELEGATED);
    }

    record Payload(String number) {
    }

    enum Outcome { APPROVE, REJECT }

    enum Step implements ProcessStepKey { REVIEW, APPROVED, REJECTED }

    static final class Approval extends ProcessDefinition<Payload, Step> {
        Approval() {
            super("approval", Payload.class);
        }

        @Override
        public TaskAssignment startAssignment(Payload payload) {
            return TaskAssignment.roles("MANAGER");
        }

        @Override
        protected void define(ProcessGraph<Payload, Step> graph) {
            var review = graph.human(Step.REVIEW, new HumanTask<Payload, Outcome>() {
                @Override public Class<Outcome> outcomeType() { return Outcome.class; }
                @Override public String title(Payload payload) { return "Review " + payload.number(); }
                @Override public TaskAssignment assignment(Payload payload) {
                    return TaskAssignment.roles("MANAGER");
                }
            });
            var approved = graph.end(Step.APPROVED);
            var rejected = graph.end(Step.REJECTED);
            graph.start().to(review);
            review.on(Outcome.APPROVE).to(approved);
            review.on(Outcome.REJECT).to(rejected);
        }
    }
}
