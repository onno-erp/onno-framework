package com.example.ui.views;

import com.example.domain.documents.Order;
import com.example.domain.enumerations.OrderStatus;
import com.example.repositories.OrderRepository;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionSpec;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

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

    public OrderView(OrderRepository orders) {
        this.orders = orders;
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
                .sortBy("date", true);
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("customer").order(0)
                .hint("Who's buying. Required.")
            .field("status").order(1)
                .hint("Lifecycle. Default «New»; advance it with the → button on the list.")
            .field("assignedTo").order(2)
                .hint("The bookseller handling this order.")
            .field("date").order(3).format("dd-MM-yyyy")
            .field("total").order(4).hideInForm().format("currency:USD")
                .hint("Auto-computed from the line amounts. Read-only.")
            .field("open").order(5).hideInForm().hideInList()
            .field("note").order(10).widget("textarea")
                .hint("Internal notes on the order.");

        // Surface "Advance" as a primary button on the detail header next to Post; Cancel goes to the ⋯ menu.
        f.action("advanceTop").primary();
    }

    @Override
    public void actions(ActionSpec a) {
        // ROW: advance to the next lifecycle status, with a dynamic "→ <next>" label.
        a.action("advance").scope(ActionScope.ROW).icon("chevron-right")
                .label(row -> {
                    OrderStatus st = row.enumValue("status", OrderStatus.class);
                    return "→ " + (st == null ? OrderStatus.CONFIRMED.name() : next(st).name());
                })
                .visibleWhen(row -> {
                    OrderStatus st = row.enumValue("status", OrderStatus.class);
                    return st != OrderStatus.COMPLETED && st != OrderStatus.CANCELLED;
                })
                .handler(ctx -> advance(ctx.id()));

        // ROW: cancel (unless terminal).
        a.action("cancel").scope(ActionScope.ROW).icon("ban").label("Cancel")
                .visibleWhen(row -> {
                    OrderStatus st = row.enumValue("status", OrderStatus.class);
                    return st != OrderStatus.CANCELLED && st != OrderStatus.COMPLETED;
                })
                .handler(ctx -> cancel(ctx.id()));

        // DETAIL: the same two as fixed-label header buttons.
        a.action("advanceTop").scope(ActionScope.DETAIL).icon("chevron-right").label("Advance")
                .handler(ctx -> advance(ctx.id()));
        a.action("cancelTop").scope(ActionScope.DETAIL).icon("ban").label("Cancel order")
                .handler(ctx -> cancel(ctx.id()));
    }

    private ActionResult advance(UUID id) {
        return orders.findById(id).map(o -> {
            OrderStatus to = o.getStatus() == null ? OrderStatus.CONFIRMED : next(o.getStatus());
            o.setStatus(to);
            orders.save(o);
            return ActionResult.refresh("Status → " + to.name());
        }).orElseGet(() -> ActionResult.message("Order not found"));
    }

    private ActionResult cancel(UUID id) {
        return orders.findById(id).map(o -> {
            o.setStatus(OrderStatus.CANCELLED);
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
