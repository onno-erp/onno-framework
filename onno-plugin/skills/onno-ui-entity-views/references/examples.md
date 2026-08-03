# Entity View And Action Examples

## Table Of Contents

- Complete EntityView
- Field Hints
- Row And Detail Actions
- Action Form
- Related List
- Map View
- Reachability And RBAC

## Complete EntityView

```java
@Component
public class SalesOrderView implements EntityView<SalesOrder> {
    private final SalesOrderRepository orders;

    public SalesOrderView(SalesOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Class<SalesOrder> entity() {
        return SalesOrder.class;
    }

    @Override
    public boolean comments() {
        return true;
    }

    @Override
    public void list(ListSpec<SalesOrder> list) {
        list.columns(SalesOrder::getNumber, SalesOrder::getDate, SalesOrder::getCustomer,
                        SalesOrder::getStatus, SalesOrder::getTotal, SalesOrder::isPosted)
                .label(SalesOrder::getNumber, "Order #")
                .label(SalesOrder::isPosted, "Posted")
                .sortBy(SalesOrder::getDate, true)
                .groupable(SalesOrder::getStatus, SalesOrder::getCustomer, SalesOrder::getDate)
                .aggregate(SalesOrder::getTotal, ListSpec.Agg.SUM, "Total");

        list.filter(SalesOrder::getStatus).label("Status").multiOptions();
        list.filter(SalesOrder::getDate).label("Order date").dateRange();

        list.rowStyle(row -> {
            OrderStatus status = row.enumValue(SalesOrder::getStatus, OrderStatus.class);
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
    public void fields(EntityConfigBuilder<SalesOrder> f) {
        f.field(SalesOrder::getNumber).label("Order #")
            .field(SalesOrder::getDate).label("Date").width("half").format("dd-MM-yyyy")
            .field(SalesOrder::isPosted).label("Posting status");
        f.refField(SalesOrder::getCustomer).order(10).width("half")
                .refSecondary(Customer::getPhone).hint("Customer on the order.");
            // Cascading picker: only this customer's delivery addresses. ${...} substitutes the
            // form's current value; empty → unfiltered; changing customer clears this field.
        f.refField(SalesOrder::getDeliveryAddress).refFilter("customer = ${customer}");
        f.field(SalesOrder::getStatus).order(20).width("half");
        f.field(SalesOrder::getTotal).order(30).format("currency:USD").hideInForm()
                .hint("Computed from lines.");
        f.field(SalesOrder::getComment).order(40).widget("textarea").width("full");

        f.rowRefField(SalesOrder::getItems, SalesOrderLine::getProduct)
                .label("Product").refSecondary(Product::getSku);
        f.rowField(SalesOrder::getItems, SalesOrderLine::getQuantity).label("Qty");
        f.rowField(SalesOrder::getItems, SalesOrderLine::getUnitPrice)
                .label("Unit price").format("currency:USD");
        f.rowField(SalesOrder::getItems, SalesOrderLine::getAmount)
                .label("Amount").format("currency:USD");

        f.action("post").primary();
        f.action("delete").inMenu();
    }

    @Override
    public void actions(ActionSpec actions) {
        actions.action("advance").scope(ActionScope.ROW).icon("chevron-right").label("Advance")
                .label(row -> "Advance to " + next(row.enumValue(SalesOrder::getStatus, OrderStatus.class)))
                .visibleWhen(row -> !terminal(row.enumValue(SalesOrder::getStatus, OrderStatus.class)))
                .handler(ctx -> advance(ctx.id()));

        actions.action("cancel").scope(ActionScope.DETAIL).icon("ban").label("Cancel")
                .visibleWhen(row -> !terminal(row.enumValue(SalesOrder::getStatus, OrderStatus.class)))
                .form(f -> f.input("reason").label("Reason").type(InputType.TEXTAREA).required())
                .handler(ctx -> cancel(ctx.id(), ctx.input("reason")));
    }

    private ActionResult advance(UUID id) {
        SalesOrder order = orders.findActiveById(id).orElseThrow();
        order.setStatus(next(order.getStatus()));
        orders.save(order);
        return ActionResult.refresh(ActionToast.success("Order advanced"));
    }

    private ActionResult cancel(UUID id, String reason) {
        SalesOrder order = orders.findActiveById(id).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setComment(reason);
        orders.save(order);
        return ActionResult.refresh(ActionToast.success("Order cancelled"));
    }
}
```

The fixed `.label("Advance")` on the row action is deliberate: the dynamic overload supplies each
row's label, while batch selection uses the fixed label for its menu and progress messages. Without
the fixed overload, those batch surfaces show the action key. If advancing a mixed-state selection
is not a well-defined operation, model separate deterministic actions instead.

## Field Hints

Use field hints to make generated forms feel authored:

```java
f.field(Customer::getCode).label("Code")
 .field(Customer::getDescription).label("Name")
 .field(Customer::getPhone).placeholder("+1 555 0100").hint("Shown in the customer picker.")
 .field(Customer::getAvatarUrl).widget("avatar")
 .field(Customer::getColor).widget("color")
 .field(Customer::getNotes).widget("textarea").width("full")
 .field(Customer::isInternal).hideInList().hideInForm().hideInDetail();
```

System columns (`code`, `description`, `number`, `date`, `posted`) need `field(...).label(...)` for
form/detail labels. `ListSpec.label(...)` only changes list headers.

## Related List

```java
@Override
public void fields(EntityConfigBuilder<Customer> f) {
    f.relatedList("contacts", CustomerContact.class)
            .via(CustomerContact::getCustomer)
            .display(CustomerContact::getContact)
            .columns(CustomerContact::getRole)
            .label("Contacts");
}
```

Use related lists when a catalog owns a set of join records. A document line owned by the document is
still a `@TabularSection`, not a related list.

## Map View

```java
@Override
public void list(ListSpec<Location> list) {
    list.columns(Location::getCode, Location::getDescription, Location::getCity, Location::getStatus);
    list.map().lat(Location::getLatitude).lng(Location::getLongitude)
            .label(Location::getDescription);
}
```

Use map view only when the entity has stable latitude and longitude attributes.

Enum status pills come from `@EnumLabel(color = "#RRGGBB")`, not from `EntityView`. For a Ref
filter, supply UUID-backed `ListSpec.Option` values; `contains()` searches the stored UUID column,
not the target's display label. UI visibility predicates are presentation only—handlers must recheck
authorization and domain preconditions.

## Reachability And RBAC

`EntityView<E>` makes DivKit list/detail routes eligible; `Layout` controls navigation. Generic REST
does not require a view. Verify shell/list/detail/New/action routes as reader, writer, unrelated role,
and `ADMIN`; readers must receive `canWrite=false` and mutations/actions must still return `403`.
