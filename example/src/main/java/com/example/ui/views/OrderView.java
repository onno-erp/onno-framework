package com.example.ui.views;

import com.example.domain.catalogs.Employee;
import com.example.domain.documents.Order;
import com.example.domain.enumerations.OrderStatus;
import com.example.repositories.EmployeeRepository;
import com.example.repositories.OrderRepository;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionSpec;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;
import su.onno.types.Ref;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The orders list — the shop's main board. The {@code status} column renders as a colored
 * {@code @EnumLabel} pill (the whole point of the enum-color feature); per-row actions advance an
 * order through its lifecycle or cancel it; {@link #comments()} opts the order into a per-document
 * discussion thread. Posting (the framework's Post button on the detail) is what actually draws
 * stock and records the sale — the status actions here just move the lifecycle flag.
 */
@Component
public class OrderView implements EntityView {

    private final OrderRepository orders;
    private final EmployeeRepository employees;

    public OrderView(OrderRepository orders, EmployeeRepository employees) {
        this.orders = orders;
        this.employees = employees;
    }

    @Override
    public Class<?> entity() {
        return Order.class;
    }

    @Override
    public boolean comments() {
        return true;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "date", "customer", "status", "total", "assignedTo")
                .label("total", "Total")
                .sortBy("date", true)
                // Group the board by status/assignee (discrete) or by date (bucketed day/month/year),
                // with the order total summed per group.
                .groupable("status", "assignedTo", "date")
                .aggregate("total", ListSpec.Agg.SUM, "Total");
        // Faceted filter bar: a status multi-select (no options authored — an enum field offers
        // every declared value, labelled like the colored pills), a date-range facet (with Today /
        // Last 7 days / This month presets) and a note typeahead. They narrow the same query the
        // grouping and search run over.
        list.filter("status").label("Status").multiOptions();
        list.filter("assignedTo").label("Assignee").multiOptions(employeeFilterOptions());
        list.filter("date").label("Order date").dateRange();
        list.filter("note").label("Note").contains();
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        // Short paired fields sit side by side on the edit form (width "half").
        f.field("customer").order(0).width("half")
                .hint("Who's buying. Required.")
            .field("status").order(1).width("half")
                .hint("Lifecycle. Default «New»; advance it with the → button on the list.")
            .field("assignedTo").order(2).width("half")
                .hint("The bookseller handling this order.")
            .field("date").order(3).width("half").format("dd-MM-yyyy")
            .field("total").order(4).hideInForm().format("currency:USD")
                .hint("Auto-computed from the line amounts. Read-only.")
            .field("open").order(5).hideInForm().hideInList().hideInDetail()
            .field("note").order(10).widget("textarea")
                .hint("Internal notes on the order.");

        // Line-item column hints, addressed with the "<section>.<field>" key (the section is "items").
        // Money columns render as currency in the order's line table, like the Total above.
        f.field("items.book").label("Book")
            .field("items.quantity").label("Qty")
            .field("items.unitPrice").label("Unit price").format("currency:USD")
            .field("items.amount").label("Amount").format("currency:USD");

        // Surface "Advance" as a primary button on the detail header next to Post; Cancel goes to the ⋯ menu.
        f.action("advanceTop").primary();
    }

    @Override
    public void actions(ActionSpec a) {
        // ROW: advance to the next lifecycle status, with a dynamic "→ <next>" label. The static
        // label is the fallback where no single row is in hand (e.g. the batch context menu).
        a.action("advance").scope(ActionScope.ROW).icon("chevron-right").label("Advance")
                .label(row -> {
                    OrderStatus st = row.enumValue("status", OrderStatus.class);
                    return "→ " + (st == null ? OrderStatus.CONFIRMED.name() : next(st).name());
                })
                .visibleWhen(row -> {
                    OrderStatus st = row.enumValue("status", OrderStatus.class);
                    return st != OrderStatus.COMPLETED && st != OrderStatus.CANCELLED;
                })
                .handler(ctx -> advance(ctx.id()));

        // ROW: cancel (unless terminal). The .form(...) makes the click open a modal asking for a
        // reason first — the handler reads it via ctx.input("reason") (the action-form feature).
        a.action("cancel").scope(ActionScope.ROW).icon("ban").label("Cancel")
                .visibleWhen(row -> {
                    OrderStatus st = row.enumValue("status", OrderStatus.class);
                    return st != OrderStatus.CANCELLED && st != OrderStatus.COMPLETED;
                })
                .form(f -> f.input("reason").label("Reason").type(su.onno.ui.InputType.TEXTAREA)
                        .placeholder("Why is this order cancelled?").required())
                .handler(ctx -> cancel(ctx.id(), ctx.input("reason")));

        // TOOLBAR: an action-form with a repeatable row group — collects N (book, quantity) lines in
        // an add/remove grid, read back via ctx.inputRows(...) (issue #248). The "book" column and the
        // "supplier" field are REFERENCE inputs: the same searchable ref picker an entity form uses,
        // so a line links a real Book/Supplier record (value is its id). Demo-only: summarizes in a
        // toast rather than writing data.
        a.action("bulkReceive").scope(ActionScope.TOOLBAR).icon("package-plus").label("Quick receive")
                .form(f -> {
                    f.input("supplier").label("Supplier").reference(com.example.domain.catalogs.Supplier.class);
                    f.input("note").label("Note").placeholder("optional");
                    f.group("lines", g -> g.label("Lines").required()
                            .column("book", c -> c.label("Book").reference(com.example.domain.catalogs.Book.class).required())
                            .column("qty", c -> c.label("Qty").type(su.onno.ui.InputType.NUMBER).required()));
                })
                .handler(this::bulkReceive);

        // DETAIL: the same two as fixed-label header buttons.
        a.action("advanceTop").scope(ActionScope.DETAIL).icon("chevron-right").label("Advance")
                .handler(ctx -> advance(ctx.id()));
        a.action("cancelTop").scope(ActionScope.DETAIL).icon("ban").label("Cancel order")
                .form(f -> f.input("reason").label("Reason").type(su.onno.ui.InputType.TEXTAREA)
                        .placeholder("Why is this order cancelled?").required())
                .handler(ctx -> cancel(ctx.id(), ctx.input("reason")));

        // CONTEXT MENU: a "Change status" submenu on the row's right-click menu — one entry per
        // status (.menu(...) keeps them out of the inline row buttons). The current status is
        // hidden per row; with a multi-row selection the entries run over every selected order.
        for (OrderStatus st : OrderStatus.values()) {
            a.action("status-" + st.name().toLowerCase()).scope(ActionScope.ROW)
                    .menu("Change status").icon("circle-dot").color(colorOf(st)).label(labelOf(st))
                    .visibleWhen(row -> row.enumValue("status", OrderStatus.class) != st)
                    .handler(ctx -> setStatus(ctx.id(), st));
        }

        // CONTEXT MENU: "Assign" shows the current active employees, using their avatar photo as
        // the menu icon. The handler writes the same @AssigneeField used by the form, so assignment
        // notifications still fire through the normal framework path.
        for (Employee employee : employees.findAllActive()) {
            if (employee.getId() == null) {
                continue;
            }
            String label = employeeLabel(employee);
            UUID employeeId = employee.getId();
            a.action("assign-" + employeeId).scope(ActionScope.ROW)
                    .menu("Assign").icon("user-round").logo(employee.getAvatarUrl()).label(label)
                    .handler(ctx -> assign(ctx.id(), employeeId, label));
        }
    }

    private ActionResult bulkReceive(su.onno.ui.ActionContext ctx) {
        var lines = ctx.inputRows("lines");
        int total = lines.stream()
                .map(r -> r.getOrDefault("qty", "0"))
                .mapToInt(q -> {
                    try {
                        return Integer.parseInt(q.trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .sum();
        return ActionResult.message(lines.size() + " line(s), " + total + " item(s) received");
    }

    private ActionResult advance(UUID id) {
        return orders.findById(id).map(o -> {
            OrderStatus to = o.getStatus() == null ? OrderStatus.CONFIRMED : next(o.getStatus());
            o.setStatus(to);
            orders.save(o);
            return ActionResult.refresh("Status → " + to.name());
        }).orElseGet(() -> ActionResult.message("Order not found"));
    }

    private ActionResult setStatus(UUID id, OrderStatus to) {
        return orders.findById(id).map(o -> {
            o.setStatus(to);
            orders.save(o);
            return ActionResult.refresh("Status → " + to.name());
        }).orElseGet(() -> ActionResult.message("Order not found"));
    }

    private ActionResult assign(UUID id, UUID employeeId, String label) {
        return orders.findById(id).map(o -> {
            o.setAssignedTo(Ref.of(Employee.class, employeeId));
            orders.save(o);
            return ActionResult.refresh("Assigned to " + label);
        }).orElseGet(() -> ActionResult.message("Order not found"));
    }

    private List<ListSpec.Option> employeeFilterOptions() {
        return employees.findAllActive().stream()
                .filter(employee -> employee.getId() != null)
                .map(employee -> ListSpec.Option.withAvatar(
                        employee.getId().toString(), employeeLabel(employee), employee.getAvatarUrl()))
                .toList();
    }

    private static String employeeLabel(Employee employee) {
        return employee.getDescription() == null || employee.getDescription().isBlank()
                ? employee.getCode()
                : employee.getDescription();
    }

    /** The @EnumLabel display value ("New", "Confirmed", …) for a submenu entry. */
    private static String labelOf(OrderStatus st) {
        return switch (st) {
            case NEW -> "New";
            case CONFIRMED -> "Confirmed";
            case SHIPPED -> "Shipped";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    /** Keep the context-menu swatches aligned with the @EnumLabel colors on OrderStatus. */
    private static String colorOf(OrderStatus st) {
        return switch (st) {
            case NEW -> "#6B7280";
            case CONFIRMED -> "#D97706";
            case SHIPPED -> "#4F46E5";
            case COMPLETED -> "#059669";
            case CANCELLED -> "#DC2626";
        };
    }

    private ActionResult cancel(UUID id, String reason) {
        return orders.findById(id).map(o -> {
            o.setStatus(OrderStatus.CANCELLED);
            // Keep the reason on the record: appended to the order's note so it shows on the detail.
            if (reason != null && !reason.isBlank()) {
                String note = o.getNote();
                o.setNote((note == null || note.isBlank() ? "" : note + "\n") + "Cancelled: " + reason);
            }
            orders.save(o);
            return ActionResult.refresh("Order cancelled");
        }).orElseGet(() -> ActionResult.message("Order not found"));
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
