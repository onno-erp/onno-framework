package su.onno.process;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypedProcessTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    @Test
    void advancesThroughTypedTaskOutcomesAndRecordsHistory() {
        PurchaseApproval definition = new PurchaseApproval();
        InMemoryProcessEngine engine =
                new InMemoryProcessEngine(Clock.fixed(NOW, ZoneOffset.UTC));

        ProcessInstance<PurchaseRequest, PurchaseStep> instance =
                engine.start(definition, new PurchaseRequest("PO-42"));

        assertThat(instance.currentStep()).isEqualTo(PurchaseStep.MANAGER_APPROVAL);
        assertThat(instance.status()).isEqualTo(ProcessStatus.ACTIVE);

        engine.complete(instance, definition.manager, ApprovalOutcome.APPROVED);
        assertThat(instance.currentStep()).isEqualTo(PurchaseStep.FINANCE_APPROVAL);

        engine.complete(instance, definition.finance, ApprovalOutcome.APPROVED);
        assertThat(instance.currentStep()).isEqualTo(PurchaseStep.COMPLETED);
        assertThat(instance.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(instance.history())
                .extracting(ProcessTransition::from, ProcessTransition::to, ProcessTransition::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                null, PurchaseStep.MANAGER_APPROVAL, null),
                        org.assertj.core.groups.Tuple.tuple(
                                PurchaseStep.MANAGER_APPROVAL,
                                PurchaseStep.FINANCE_APPROVAL,
                                ApprovalOutcome.APPROVED),
                        org.assertj.core.groups.Tuple.tuple(
                                PurchaseStep.FINANCE_APPROVAL,
                                PurchaseStep.COMPLETED,
                                ApprovalOutcome.APPROVED));
    }

    @Test
    void rejectsAProcessThatDoesNotHandleEveryTaskOutcome() {
        ProcessDefinition<PurchaseRequest, PurchaseStep> incomplete = new ProcessDefinition<>() {
            @Override
            protected void define(ProcessGraph<PurchaseRequest, PurchaseStep> graph) {
                var manager = graph.human(PurchaseStep.MANAGER_APPROVAL, new ApprovalTask());
                var completed = graph.end(PurchaseStep.COMPLETED);
                graph.start().to(manager);
                manager.on(ApprovalOutcome.APPROVED).to(completed);
            }
        };

        assertThatThrownBy(incomplete::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("MANAGER_APPROVAL")
                .hasMessageContaining("REJECTED");
    }

    @Test
    void rejectsUnreachableStepsAndCrossGraphConnections() {
        ProcessDefinition<PurchaseRequest, PurchaseStep> unreachable = new ProcessDefinition<>() {
            @Override
            protected void define(ProcessGraph<PurchaseRequest, PurchaseStep> graph) {
                graph.start().to(graph.end(PurchaseStep.COMPLETED));
                graph.end(PurchaseStep.REJECTED);
            }
        };

        assertThatThrownBy(unreachable::graph)
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("Unreachable")
                .hasMessageContaining("REJECTED");

        ProcessGraph<PurchaseRequest, PurchaseStep> left = new ProcessGraph<>();
        ProcessGraph<PurchaseRequest, PurchaseStep> right = new ProcessGraph<>();
        var leftEnd = left.end(PurchaseStep.COMPLETED);
        var rightEnd = right.end(PurchaseStep.REJECTED);

        assertThatThrownBy(() -> left.start().to(rightEnd))
                .isInstanceOf(InvalidProcessDefinitionException.class)
                .hasMessageContaining("different process graphs");
        left.start().to(leftEnd);
    }

    @Test
    void refusesToCompleteATaskThatIsNotActive() {
        PurchaseApproval definition = new PurchaseApproval();
        InMemoryProcessEngine engine = new InMemoryProcessEngine();
        ProcessInstance<PurchaseRequest, PurchaseStep> instance =
                engine.start(definition, new PurchaseRequest("PO-43"));

        assertThatThrownBy(() ->
                engine.complete(instance, definition.finance, ApprovalOutcome.APPROVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    record PurchaseRequest(String orderNumber) {
    }

    enum ApprovalOutcome {
        APPROVED,
        REJECTED
    }

    enum PurchaseStep implements ProcessStepKey {
        MANAGER_APPROVAL,
        FINANCE_APPROVAL,
        COMPLETED,
        REJECTED
    }

    static final class ApprovalTask implements HumanTask<PurchaseRequest, ApprovalOutcome> {
        @Override
        public Class<ApprovalOutcome> outcomeType() {
            return ApprovalOutcome.class;
        }
    }

    static final class PurchaseApproval
            extends ProcessDefinition<PurchaseRequest, PurchaseStep> {

        private HumanTaskNode<PurchaseRequest, PurchaseStep, ApprovalOutcome> manager;
        private HumanTaskNode<PurchaseRequest, PurchaseStep, ApprovalOutcome> finance;

        @Override
        protected void define(ProcessGraph<PurchaseRequest, PurchaseStep> graph) {
            manager = graph.human(PurchaseStep.MANAGER_APPROVAL, new ApprovalTask());
            finance = graph.human(PurchaseStep.FINANCE_APPROVAL, new ApprovalTask());
            var completed = graph.end(PurchaseStep.COMPLETED);
            var rejected = graph.end(PurchaseStep.REJECTED);

            graph.start().to(manager);
            manager.on(ApprovalOutcome.APPROVED).to(finance);
            manager.on(ApprovalOutcome.REJECTED).to(rejected);
            finance.on(ApprovalOutcome.APPROVED).to(completed);
            finance.on(ApprovalOutcome.REJECTED).to(rejected);
        }
    }
}
