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

    private CommandResult requestApproval(UUID orderId, String username) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order: " + orderId));
        var payload = new OrderApprovalProcess.Payload(
                orderId, order.getNumber(), order.getTotal(), order.getAssignedTo());
        processes.start(orderApproval, payload, new ProcessActor(username, java.util.Set.of()));
        return CommandResult.success("Approval task created");
    }

    private CommandResult bulkReceive(CommandContext ctx) {
        var lines = ctx.inputRows("lines");
        int total = lines.stream()
                .map(r -> r.getOrDefault("qty", "0"))
                .mapToInt(q -> {
                    try {
                        return (int) Double.parseDouble(q.trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .sum();
        return CommandResult.success(lines.size() + " line(s), " + total + " item(s) received");
    }

    private CommandResult feedbackDemo(CommandContext ctx) {
        String outcome = ctx.input("outcome");
        String note = ctx.input("note");
        return switch (outcome == null ? "" : outcome) {
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
            default -> CommandResult.success("Typed successful feedback with details.")
                    .title("Demo completed")
                    .detail(note == null || note.isBlank() ? "No test note was supplied." : "Test note: " + note);
        };
    }

    private CommandResult toastDemo() {
        return switch (Math.floorMod(toastDemoSequence.getAndIncrement(), 4)) {
            case 0 -> CommandResult.success("Everything passed validation and the order can move forward.")
                    .title("Order ready");
            case 1 -> CommandResult.info("You can keep working while the latest sales data is refreshed.")
                    .title("Background sync started");
            case 2 -> CommandResult.warning("Two order lines need attention before the next shipment.")
                    .title("Stock is running low");
            default -> CommandResult.error("The carrier rejected the hand-off. No order data was changed.")
                    .title("Shipment could not be completed")
                    .details(List.of("Check the delivery address", "Try again when the carrier is available"));
        };
    }

    private CommandResult advance(UUID id) {
        return orders.findById(id).map(o -> {
            OrderStatus to = o.getStatus() == null ? OrderStatus.CONFIRMED : next(o.getStatus());
            o.setStatus(to);
            orders.save(o);
            return CommandResult.success("Status → " + to.name()).andRefresh();
        }).orElseGet(() -> CommandResult.warning("Order not found"));
    }

    private CommandResult setStatus(UUID id, OrderStatus to) {
        return orders.findById(id).map(o -> {
            o.setStatus(to);
            orders.save(o);
            return CommandResult.success("Status → " + to.name()).andRefresh();
        }).orElseGet(() -> CommandResult.warning("Order not found"));
    }

    private CommandResult assign(UUID id, UUID employeeId, String label) {
        return orders.findById(id).map(o -> {
            o.setAssignedTo(Ref.of(Employee.class, employeeId));
            orders.save(o);
            return CommandResult.success("Assigned to " + label).andRefresh();
        }).orElseGet(() -> CommandResult.warning("Order not found"));
    }

    private CommandResult cancel(UUID id, String reason) {
        return orders.findById(id).map(o -> {
            o.setStatus(OrderStatus.CANCELLED);
            // Keep the reason on the record: appended to the order's note.
            if (reason != null && !reason.isBlank()) {
                String note = o.getNote();
                o.setNote((note == null || note.isBlank() ? "" : note + "\n") + "Cancelled: " + reason);
            }
            orders.save(o);
            return CommandResult.success("Order cancelled").andRefresh();
        }).orElseGet(() -> CommandResult.warning("Order not found"));
    }

    private static String employeeLabel(Employee employee) {
        return employee.getDescription() == null || employee.getDescription().isBlank()
                ? employee.getCode()
                : employee.getDescription();
    }

    /** The @EnumLabel display value ("New", "Confirmed", …). */
    private static String labelOf(OrderStatus st) {
        return switch (st) {
            case NEW -> "New";
            case CONFIRMED -> "Confirmed";
            case SHIPPED -> "Shipped";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static boolean terminal(OrderStatus st) {
        return st == OrderStatus.COMPLETED || st == OrderStatus.CANCELLED;
    }

    private static OrderStatus next(OrderStatus s) {
        return switch (s) {
            case NEW -> OrderStatus.CONFIRMED;
            case CONFIRMED -> OrderStatus.SHIPPED;
            case SHIPPED -> OrderStatus.COMPLETED;
            case COMPLETED, CANCELLED -> s;
        };
    }
}
