# Migrating to typed fields and durable processes

Use this checklist when upgrading an application or instructing a coding agent. The intended end
state is that Java model fields are referenced by getters; strings remain only for semantic
identifiers, derived wire columns, and expression text.

## Entity views

Give every view its entity type:

```java
final class OrderView implements EntityView<Order> {
    public Class<Order> entity() { return Order.class; }

    public void list(ListSpec<Order> list) {
        list.columns(Order::getNumber, Order::getDate, Order::getStatus)
                .sortBy(Order::getDate, true)
                .groupable(Order::getStatus)
                .filter(Order::getStatus).multiOptions();
    }

    public void fields(EntityConfigBuilder<Order> fields) {
        fields.field(Order::getStatus).label("Status");
        fields.rowField(Order::getItems, OrderLine::getQuantity).label("Qty");
        fields.refField(Order::getCustomer)
                .refSecondary(Customer::getPhone);
    }
}
```

Use `refField`/`rowRefField` when configuring a `Ref<T>` and then naming a field on `T`; this is what
lets the compiler reject `Supplier::getEmail` on a `Ref<Customer>`. Related lists are typed through
the owner and junction:

```java
fields.relatedList("orders", Order.class)
        .via(Order::getCustomer)
        .display(Order::getSalesperson)
        .columns(Order::getStatus, Order::getTotal);
```

State-aware UI functions accept the same getters:

```java
list.rowStyle(row ->
        row.enumValue(Order::getStatus, OrderStatus.class) == OrderStatus.CANCELLED
                ? ListSpec.RowStyle.MUTED : null);
```

For live form validation, use header getters and typed tabular paths:

```java
fields.validation("schedule", ScheduleValidator.class)
        .dependsOn(Event::getStartsAt, Event::getEndsAt)
        .andDependsOn(Event::getParticipants, Participant::getEmployee);
```

## Pages and widgets

Select actual model fields with dedicated typed methods:

```java
b.widget("Revenue").type("chart").document(Order.class)
        .dateField(Order::getDate)
        .groupBy(Order::getStatus)
        .metricField(Order::getTotal);
```

The resolver converts Java names to REST/storage columns at the boundary (`date` → `_date`,
`startsAt` → `starts_at`). Keep `config(...)` for widget behavior (`kind`, `currency`,
`groupByDate`) and custom-widget contracts. A derived response key such as `status_display` has no
Java getter and therefore remains an explicit string.

## Queries and registers

Import `su.onno.fields.Field` for reusable field tokens. The old
`su.onno.repository.FieldReference` is only a deprecated alias. `RegisterQueryBuilder` and
`RegisterFilter` use the shared field type:

```java
query.groupBy(Stock::getWarehouse).where(Stock::getProduct, product);
```

## Business processes

Make each process definition a Spring bean with a stable persisted key, positive definition
version, explicit payload class, and explicit start authorization. The legacy constructor below is
version 1:

```java
@Component
final class OrderApproval extends ProcessDefinition<Payload, Step> {
    OrderApproval() {
        super("order-approval", Payload.class);
    }

    public TaskAssignment startAssignment(Payload payload) {
        return TaskAssignment.roles("MANAGER");
    }

    protected void define(ProcessGraph<Payload, Step> graph) {
        var review = graph.human(Step.REVIEW, new ReviewTask());
        var approved = graph.end(Step.APPROVED);
        var rejected = graph.end(Step.REJECTED);
        graph.start().to(review);
        review.on(Outcome.APPROVE).to(approved);
        review.on(Outcome.REJECT).to(rejected);
    }
}
```

Every `HumanTask<P,O>` must declare `outcomeType()` and `assignment(payload)`; optionally override
`title(payload)`. Start and complete from Java with typed values:

```java
ProcessSnapshot process = engine.start(
        definition, payload, new ProcessActor(username, roles));
engine.complete(workItemId, Outcome.APPROVE, actor);
```

Automatic employee routing belongs in `assignment(payload)`: call a typed routing service and
return `TaskAssignment.identities(router.approverFor(payload))`, where the result is a
`Ref<Employee>`. Add `subject(payload)` when the task concerns a catalog/document record and
`subjectLabel(payload)` when its inbox link needs a stable human label:

```java
public Ref<Order> subject(Payload payload) {
    return payload.order();
}

public String subjectLabel(Payload payload) {
    return "Order " + payload.orderNumber();
}
```

The employee record UUID—not its mutable login/email—is persisted as the owner.

Instances, transition history, candidates, claims, delegation reasons, task audit events, and
completions are durable. A claimed task's assignee can call
`engine.delegate(workItemId, targetIdentity, reason, actor)`. HTTP callers select the
`targetActorId` returned by `/api/task-assignees`. Put
`b.widget("My tasks").type("tasks")` on a page for the human inbox. Headless clients use
`/api/processes/**` and `/api/tasks/**`; only the JSON completion boundary uses an enum constant
name string.

The graph now also supports:

```java
var prepare = graph.automatic(Step.PREPARE, (payload, execution) -> service.prepare(payload));
var route = graph.decision(Step.ROUTE, new TypedDecision<Payload, Route>() {
    public Class<Route> outcomeType() { return Route.class; }
    public Route decide(Payload payload) { return policy.route(payload); }
});
var timeout = graph.timer(Step.WAIT, ProcessTimer.after(Duration.ofHours(2)));
var parallel = graph.parallel(Step.FORK, Branch.class, Step.JOIN);
var child = graph.subprocess(Step.CHILD, childCall);
```

Automatic steps must return a non-null payload. They execute transactionally and receive a stable
idempotency key; use an outbox for non-idempotent external calls. Decisions and parallel branches
must route every enum constant. Every parallel path must reach `parallel.join()`. A subprocess must
route every child ending plus `onCancellation()`.

Do not change a persisted graph under the same version. Keep the old bean, add the new version with
`super("order-approval", 2, NewPayload.class)`, and register a
`ProcessDefinitionMigration<OldPayload,OldStep,NewPayload,NewStep>` bean. Its result must map every
active source token id:

```java
return new ProcessMigrationResult<>(
        migratePayload(state.payload()),
        state.tokens().stream().collect(toMap(
                ProcessMigrationToken::tokenId,
                token -> NewStep.valueOf(token.step().name()))));
```

Then call `engine.migrate(instanceId, actor)` (or the authenticated migrate endpoint). Migration
is atomic and preserves branch ancestry. An active parallel coordinator must map to a parallel
fork with the same branch enum keys; finish or cancel the instance before changing branch
cardinality. `engine.cancel(instanceId, reason, actor)` closes live human work, timers, parallel
tokens, and subprocess descendants with an audit trail.

## Strings that intentionally remain

- route, action, role, stable process, and widget type keys;
- external authentication subjects when no identity catalog is configured;
- human labels, hints, and formatting patterns;
- parsed filter expressions and their runtime values;
- derived/synthetic wire columns such as `status_display`;
- dynamic metadata loaded at runtime, using the documented unsafe string overloads.

Do not turn those into fake Java types. Conversely, if a string names a real Java field in authored
application code, replace it with a getter reference.
