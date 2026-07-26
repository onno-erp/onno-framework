package com.example.process;

import org.springframework.stereotype.Component;
import su.onno.process.HumanTask;
import su.onno.process.ProcessDefinition;
import su.onno.process.ProcessGraph;
import su.onno.process.ProcessStepKey;
import su.onno.process.TaskAssignment;

import java.math.BigDecimal;
import java.util.UUID;
import com.example.domain.catalogs.Employee;
import com.example.domain.documents.Order;
import su.onno.types.Ref;

/** Small real process used by the example app's order action and task inbox. */
@Component
public final class OrderApprovalProcess
        extends ProcessDefinition<OrderApprovalProcess.Payload, OrderApprovalProcess.Step> {

    public OrderApprovalProcess() {
        super("order-approval", Payload.class);
    }

    @Override
    public String title() {
        return "Order approval";
    }

    @Override
    public TaskAssignment startAssignment(Payload payload) {
        return TaskAssignment.roles("MANAGER");
    }

    @Override
    protected void define(ProcessGraph<Payload, Step> graph) {
        var review = graph.human(Step.REVIEW, new ReviewTask());
        var approved = graph.end(Step.APPROVED);
        var rejected = graph.end(Step.REJECTED);
        graph.start().to(review);
        review.on(Outcome.APPROVE).to(approved);
        review.on(Outcome.REJECT).to(rejected);
    }

    public record Payload(
            UUID orderId,
            String orderNumber,
            BigDecimal total,
            Ref<Employee> assignedTo
    ) {
    }

    public enum Step implements ProcessStepKey {
        REVIEW {
            @Override public String key() { return "review"; }
        },
        APPROVED {
            @Override public String key() { return "approved"; }
        },
        REJECTED {
            @Override public String key() { return "rejected"; }
        }
    }

    public enum Outcome {
        APPROVE,
        REJECT
    }

    private static final class ReviewTask implements HumanTask<Payload, Outcome> {
        @Override
        public Class<Outcome> outcomeType() {
            return Outcome.class;
        }

        @Override
        public String title(Payload payload) {
            return "Review order " + payload.orderNumber();
        }

        @Override
        public TaskAssignment assignment(Payload payload) {
            return payload.assignedTo() == null
                    ? TaskAssignment.roles("MANAGER")
                    : TaskAssignment.identities(payload.assignedTo());
        }

        @Override
        public Ref<Order> subject(Payload payload) {
            return Ref.of(Order.class, payload.orderId());
        }

        @Override
        public String subjectLabel(Payload payload) {
            return "Order " + payload.orderNumber();
        }
    }
}
