# Command, check and picker examples

From the reference app, `example/src/main/java/com/example/` (`commands/`, `checks/`, `pickers/`,
`IdentityConfiguration.java`), and how `example/src/main/ui/entities/orders.ts` offers them.

- OrderCommands: record, grouped, dynamic and collection commands
- Offering the commands in the UI
- Server-computed input defaults
- ScheduleEventChecks: live conflict warnings
- EmployeeAvailability: picker badges
- IdentityLink

## OrderCommands: record, grouped, dynamic and collection commands

```java
package com.example.commands;

import com.example.domain.catalogs.Book;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Supplier;
import com.example.domain.documents.Order;
import com.example.domain.enumerations.OrderStatus;
import com.example.process.OrderApprovalProcess;
import com.example.repositories.EmployeeRepository;
import com.example.repositories.OrderRepository;
import su.onno.command.CommandContext;
import su.onno.command.CommandRejectedException;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.process.ProcessActor;
import su.onno.process.ProcessEngine;
import su.onno.types.Ref;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What can be done with an {@link Order} besides editing it: advance it through its lifecycle,
 * cancel it with a reason, set its status or assignee directly, start an approval process, and
 * collection-level demos of row-group inputs and feedback. Posting (the framework's Post) is
 * what actually draws stock and records the sale — these commands only move the lifecycle flag.
 *
 * <p>Every command works the same from the UI, the REST API and MCP; roles and per-record
 * availability are enforced on the server. How the UI presents them is the React client's
 * business.</p>
 */
@Component
public class OrderCommands implements Commands<Order> {

    private final OrderRepository orders;
    private final EmployeeRepository employees;
    private final ProcessEngine processes;
    private final OrderApprovalProcess orderApproval;
    private final AtomicInteger toastDemoSequence = new AtomicInteger();

    public OrderCommands(OrderRepository orders, EmployeeRepository employees,
                         ProcessEngine processes, OrderApprovalProcess orderApproval) {
        this.orders = orders;
        this.employees = employees;
        this.processes = processes;
        this.orderApproval = orderApproval;
    }

    @Override
    public Class<Order> entity() {
        return Order.class;
    }

    @Override
    public void define(CommandSpec<Order> c) {
        c.command("requestApproval").title("Request approval")
                .description("Starts the order approval process; a manager gets an approval task.")
                .roles("MANAGER")
                .run(ctx -> requestApproval(ctx.id(), ctx.user()));

        // Advance to the next lifecycle status, titled "→ <next>" per order. The fixed title is what
        // surfaces without a single order in hand (a batch over a selection, an MCP tool) show.
        c.command("advance").title("Advance")
                .title(row -> {
                    OrderStatus st = row.enumValue(Order::getStatus, OrderStatus.class);
                    return "→ " + (st == null ? OrderStatus.CONFIRMED.name() : next(st).name());
                })
                .description("Moves the order to its next lifecycle status (New → Confirmed → Shipped → Completed).")
                .availableWhen(row -> !terminal(row.enumValue(Order::getStatus, OrderStatus.class)))
                .run(ctx -> advance(ctx.id()));

        // Cancel (unless terminal), asking for a reason first.
        c.command("cancel").title("Cancel order")
                .description("Cancels the order and records the reason in its note.")
                .availableWhen(row -> !terminal(row.enumValue(Order::getStatus, OrderStatus.class)))
                .input("reason", i -> i.title("Reason").longText().required())
                .run(ctx -> cancel(ctx.id(), ctx.input("reason")));

        // One command per status, grouped: a UI shows them as a "Change status" submenu.
        for (OrderStatus st : OrderStatus.values()) {
            c.command("status-" + st.name().toLowerCase()).group("Change status").title(labelOf(st))
                    .description("Sets the order's status to " + labelOf(st) + ".")
                    .availableWhen(row -> row.enumValue(Order::getStatus, OrderStatus.class) != st)
                    .run(ctx -> setStatus(ctx.id(), st));
        }

        // One command per active employee, read from the catalog each time the commands are
        // listed or run. It writes the same @AssigneeField the form uses, so assignment
        // notifications still fire through the normal framework path.
        c.dynamic(live -> {
            for (Employee employee : employees.findAllActive()) {
                if (employee.getId() == null) {
                    continue;
                }
                String label = employeeLabel(employee);
                UUID employeeId = employee.getId();
                live.command("assign-" + employeeId).group("Assign").title(label)
                        .description("Assigns the order to " + label + ".")
                        .run(ctx -> assign(ctx.id(), employeeId, label));
            }
        });

        // A collection command with a repeatable row group: N (book, quantity) lines, read back
        // via ctx.inputRows(...). The "book" column and the "supplier" input are references (the
        // value is the picked record's id). Demo-only: summarizes in a toast, writes nothing.
        c.collectionCommand("bulkReceive").title("Quick receive")
                .description("Demo: collects received lines and summarizes them; no data is changed.")
                .input("supplier", i -> i.title("Supplier").reference(Supplier.class))
                .input("note", i -> i.title("Note"))
                .inputRows("lines", g -> g.title("Lines").required()
                        .column("book", col -> col.title("Book").reference(Book.class).required())
                        .column("qty", col -> col.title("Qty").number().required()))
                .run(this::bulkReceive);

        // A deterministic showcase of command feedback: results and a rejection, no data changes.
        // The server says what happened; the client picks a toast, a dialog or the open form.
        c.collectionCommand("feedbackDemo").title("Feedback demo")
                .description("Demo: returns the chosen kind of feedback; no data is changed.")
                .input("outcome", i -> i.title("Outcome")
                        .oneOf("Success with details", "Rejection", "Error", "Warning")
                        .defaultValue("Success with details").required())
                .input("note", i -> i.title("Test note"))
                .run(this::feedbackDemo);

        // Repeated runs cycle through all severities.
        c.collectionCommand("toastDemo").title("Severity demo")
                .description("Demo: reports an outcome, cycling through the severities.")
                .run(ctx -> toastDemo());
    }

    // ... the private handlers (advance, cancel, setStatus, assign, bulkReceive, feedbackDemo,
    // toastDemo) load the order, change it, save it and return CommandResult.success(...).andRefresh().
}
```

A handler:

```java
private CommandResult cancel(UUID id, String reason) {
    return orders.findById(id).map(o -> {
        o.setStatus(OrderStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            String note = o.getNote();
            o.setNote((note == null || note.isBlank() ? "" : note + "\n") + "Cancelled: " + reason);
        }
        orders.save(o);
        return CommandResult.success("Order cancelled").andRefresh();
    }).orElseGet(() -> CommandResult.warning("Order not found"));
}
```

Feedback of every kind, and a rejection that keeps the input dialog open:

```java
private CommandResult feedbackDemo(CommandContext ctx) {
    String outcome = ctx.input("outcome");
    return switch (outcome) {
        case "Rejection" -> throw CommandRejectedException.builder()
                .title("Demo command blocked")
                .message("The form stayed open and retained the test note.")
                .formError("This is a purposeful business-rule rejection.")
                .fieldError("note", "Edit this note and submit again, or choose another outcome.")
                .build();
        case "Error" -> CommandResult.error("No data was changed.")
                .title("Demo command failed")
                .detail("This is structured detail returned by the server.");
        case "Warning" -> CommandResult.warning("This is a warning; the command ran with a caveat.");
        default -> CommandResult.success("Typed successful feedback with details.").title("Demo completed");
    };
}
```

Reading a row group:

```java
private CommandResult bulkReceive(CommandContext ctx) {
    List<Map<String, String>> lines = ctx.inputRows("lines");   // [{book: "<uuid>", qty: "3"}, …]
    return CommandResult.success(lines.size() + " line(s) received");
}
```

## Offering the commands in the UI

`example/src/main/ui/entities/orders.ts`:

```ts
commands: {
  advance: { placement: "row", icon: "arrow-right" },   // inline on every row + the menus
  cancel: { icon: "ban" },
  requestApproval: { icon: "badge-check" },
  bulkReceive: { icon: "package-plus" },                 // collection command → list toolbar
  feedbackDemo: { icon: "message-square-warning" },
  toastDemo: { icon: "bell" },
},
```

Unlisted commands (the `Change status` and `Assign` groups) keep the default: record header menu
and row context menu, one submenu per group. From code: `useCommand("Orders", "cancel")
.mutate({ id, inputs: { reason } })`.

Over MCP the same commands are the tools `Orders_requestApproval`, `Orders_advance`,
`Orders_cancel` (input `reason`, required `id`), `Orders_status-new`, …, `Orders_bulkReceive`
(`supplier`, `note`, array `lines`); the dynamic `assign-<uuid>` commands are not published.

## Server-computed input defaults

```java
c.collectionCommand("restock").title("Restock")
        .description("Orders more copies of low-stock books from their suppliers.")
        .inputRows("lines", g -> g.title("Lines").required()
                .column("book", col -> col.title("Book").reference(Book.class).required())
                .column("qty", col -> col.title("Qty").number().required()))
        .defaults(ctx -> CommandDefaults.ofRows("lines", lowStockLines()))   // List<Map<String, String>>
        .run(this::restock);
```

## ScheduleEventChecks: live conflict warnings

```java
@Component
public class ScheduleEventChecks implements Checks<ScheduleEvent> {

    private final ScheduleEventRepository schedules;

    public ScheduleEventChecks(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public void define(CheckSpec<ScheduleEvent> v) {
        v.check("schedule-conflicts", this::conflicts)
                .dependsOn(ScheduleEvent::getStartsAt, ScheduleEvent::getEndsAt)
                .andDependsOn(ScheduleEvent::getParticipants, ScheduleParticipant::getEmployee)
                .debounce(Duration.ofMillis(200));
    }

    private List<Feedback> conflicts(CheckContext context) {
        LocalDateTime start = temporal(context.values().get("startsAt"));
        LocalDateTime end = temporal(context.values().get("endsAt"));
        if (start == null || end == null || !end.isAfter(start)) {
            return List.of(Feedback.error("endsAt", "End time must be after start time"));
        }
        long overlaps = schedules.findAllActive().stream()
                .filter(event -> context.id() == null || !context.id().equals(event.getId()))
                .filter(event -> overlaps(start, end, event))
                .count();
        if (overlaps == 0) {
            return List.of(Feedback.info("", "No schedule conflicts found"));
        }
        String message = "Time overlaps with " + overlaps
                + (overlaps == 1 ? " scheduled event" : " scheduled events");
        return List.of(
                Feedback.warning("startsAt", message),
                Feedback.info("participants.employee",
                        "Check availability statuses before adding participants"));
    }

    private static boolean overlaps(LocalDateTime start, LocalDateTime end, ScheduleEvent event) {
        return event.getStartsAt() != null && event.getEndsAt() != null
                && start.isBefore(event.getEndsAt()) && end.isAfter(event.getStartsAt());
    }

    private static LocalDateTime temporal(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value == null || value.toString().isBlank()) return null;
        return LocalDateTime.parse(value.toString());
    }
}
```

## EmployeeAvailability: picker badges

```java
@Component
public class EmployeeAvailability implements PickerOptions<ScheduleEvent> {

    private final ScheduleEventRepository schedules;

    public EmployeeAvailability(ScheduleEventRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public Class<ScheduleEvent> entity() {
        return ScheduleEvent.class;
    }

    @Override
    public String field() {
        return "participants.employee";
    }

    @Override
    public Map<UUID, PickerOption> decorate(PickerContext context, List<PickerCandidate> options) {
        LocalDateTime startsAt = temporal(context.formValues().get("startsAt"));
        LocalDateTime endsAt = temporal(context.formValues().get("endsAt"));
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            Map<UUID, PickerOption> waiting = new LinkedHashMap<>();
            for (PickerCandidate option : options) {
                waiting.put(option.id(), PickerOption.badge(
                        "Choose a time", PickerTone.WARNING));
            }
            return waiting;
        }

        Map<UUID, String> conflicts = new LinkedHashMap<>();
        for (ScheduleEvent event : schedules.findAllActive()) {
            if (context.recordId() != null && context.recordId().equals(event.getId())) {
                continue;
            }
            if (!overlaps(startsAt, endsAt, event.getStartsAt(), event.getEndsAt())) {
                continue;
            }
            event.getParticipants().stream()
                    .filter(row -> row.getEmployee() != null)
                    .forEach(row -> conflicts.putIfAbsent(
                            row.getEmployee().id(),
                            "Conflicts with " + event.getNumber() + " · " + event.getSubject()));
        }

        Map<UUID, PickerOption> result = new LinkedHashMap<>();
        boolean showUnavailable = !Boolean.FALSE.equals(context.formValues().get("showUnavailable"));
        for (PickerCandidate option : options) {
            String conflict = conflicts.get(option.id());
            if (conflict != null && !showUnavailable) {
                result.put(option.id(), PickerOption.filteredOut());
            } else {
                result.put(option.id(), conflict == null
                        ? PickerOption.badge("Available", PickerTone.SUCCESS)
                        : PickerOption.disabled(
                                "Unavailable", PickerTone.DANGER, conflict));
            }
        }
        return result;
    }

    private static boolean overlaps(LocalDateTime start, LocalDateTime end,
                                    LocalDateTime otherStart, LocalDateTime otherEnd) {
        return otherStart != null && otherEnd != null
                && start.isBefore(otherEnd) && end.isAfter(otherStart);
    }

    private static LocalDateTime temporal(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.toString());
    }
}
```

The form side (`entities/scheduleEvents.ts`) only adds presentation:
`"participants.employee": { label: "Employee", refSecondary: "email", uniqueWithinSection: true }`
and `showUnavailable: { input: "switch" }`, the field the decorator reads to hide or disable
conflicting employees.

## IdentityLink

```java
@Configuration
public class IdentityConfiguration {

    @Bean
    IdentityLink identity() {
        return IdentityLink.of(Employee.class, "email");
    }
}
```
