# Entity View And Action Examples

## Table Of Contents

- Complete EntityView
- Field Hints
- Row And Detail Actions
- Action Form
- Related List
- Map View

## Complete EntityView

```java
@Component
public class SalesOrderView implements EntityView {
    private final SalesOrderRepository orders;

    public SalesOrderView(SalesOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Class<?> entity() {
        return SalesOrder.class;
    }

    @Override
    public boolean comments() {
        return true;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "date", "customer", "status", "total", "posted")
                .label("number", "Order #")
                .label("posted", "Posted")
                .sortBy("date", true)
                .groupable("status", "customer", "date")
                .aggregate("total", ListSpec.Agg.SUM, "Total");

        list.filter("status").label("Status").multiOptions();
        list.filter("date").label("Order date").dateRange();
        list.filter("customer").label("Customer").contains();

        list.rowStyle(row -> {
            OrderStatus status = row.enumValue("status", OrderStatus.class);
            if (status == OrderStatus.CANCELLED) {
                return ListSpec.RowStyle.MUTED;
            }
            if (status == OrderStatus.DRAFT) {
                return ListSpec.RowStyle.WARNING;
            }
            return null;
        });
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("number").label("Order #")
            .field("date").label("Date").width("half").format("dd-MM-yyyy")
            .field("posted").label("Posting status")
            .field("customer").order(10).width("half").refSecondary("phone")
                .hint("Customer on the order.")
            // Cascading picker: only this customer's delivery addresses. ${...} substitutes the
            // form's current value; empty → unfiltered; changing customer clears this field.
            .field("deliveryAddress").refFilter("customer = ${customer}")
            .field("status").order(20).width("half")
            .field("total").order(30).format("currency:USD").hideInForm()
                .hint("Computed from lines.")
            .field("comment").order(40).widget("textarea").width("full");

        f.field("items.product").label("Product")
            .field("items.quantity").label("Qty")
            .field("items.unitPrice").label("Unit price").format("currency:USD")
            .field("items.amount").label("Amount").format("currency:USD");

        f.action("post").primary();
        f.action("delete").inMenu();
    }

    @Override
    public void actions(ActionSpec actions) {
        actions.action("advance").scope(ActionScope.ROW).icon("chevron-right").label("Advance")
                .label(row -> "Advance to " + next(row.enumValue("status", OrderStatus.class)))
                .visibleWhen(row -> !terminal(row.enumValue("status", OrderStatus.class)))
                .handler(ctx -> advance(ctx.id()));

        actions.action("cancel").scope(ActionScope.DETAIL).icon("ban").label("Cancel")
                .visibleWhen(row -> !terminal(row.enumValue("status", OrderStatus.class)))
                .form(f -> f.input("reason").label("Reason").type(InputType.TEXTAREA).required())
                .handler(ctx -> cancel(ctx.id(), ctx.input("reason")));
    }

    private ActionResult advance(UUID id) {
        SalesOrder order = orders.findActiveById(id).orElseThrow();
        order.setStatus(next(order.getStatus()));
        orders.save(order);
        return ActionResult.success("Order advanced");
    }

    private ActionResult cancel(UUID id, String reason) {
        SalesOrder order = orders.findActiveById(id).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setComment(reason);
        orders.save(order);
        return ActionResult.success("Order cancelled");
    }
}
```

## Field Hints

Use field hints to make generated forms feel authored:

```java
f.field("code").label("Code")
 .field("description").label("Name")
 .field("phone").placeholder("+1 555 0100").hint("Shown in the customer picker.")
 .field("avatarUrl").widget("avatar")
 .field("notes").widget("textarea").width("full")
 .field("internalFlag").hideInList().hideInForm().hideInDetail();
```

System columns (`code`, `description`, `number`, `date`, `posted`) need `field(...).label(...)` for
form/detail labels. `ListSpec.label(...)` only changes list headers.

## Related List

```java
@Override
public void fields(EntityConfigBuilder f) {
    f.relatedList("contacts", CustomerContact.class)
            .via("customer")
            .display("contact")
            .label("Contacts");
}
```

Use related lists when a catalog owns a set of join records. A document line owned by the document is
still a `@TabularSection`, not a related list.

## Map View

```java
@Override
public void list(ListSpec list) {
    list.columns("code", "description", "city", "status");
    list.map().lat("latitude").lng("longitude").label("description");
}
```

Use map view only when the entity has stable latitude and longitude attributes.
