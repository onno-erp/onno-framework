# Documents And Lines Examples

## Table Of Contents

- Complete Sales Order
- Tabular Section Row
- Status Enumeration
- Typed Entity View
- Repository
- Runtime Verification
- Document Or Something Else
- Common Mistakes

## Complete Sales Order

```java
package com.acme.sales.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(name = "SalesOrders", title = "Sales orders", numberPrefix = "SO-", context = "Sales")
@AccessControl(readRoles = {"SALES", "ADMIN"}, writeRoles = {"SALES", "ADMIN"})
@Getter
@Setter
public class SalesOrder extends DocumentObject
        implements OnFillingHandler, BeforeWriteHandler, Validated, Postable {

    @Attribute(displayName = "Customer", required = true)
    private Ref<Customer> customer;

    @Attribute(displayName = "Status")
    private OrderStatus status = OrderStatus.DRAFT;

    @Attribute(displayName = "Total", precision = 15, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Attribute(displayName = "Comment", length = 1000)
    private String comment;

    @TabularSection(name = "items")
    private List<SalesOrderLine> items = new ArrayList<>();

    @Override
    public void onFilling() {
        if (getDate() == null) {
            setDate(LocalDateTime.now());
        }
        if (status == null) {
            status = OrderStatus.DRAFT;
        }
    }

    @Override
    public void beforeWrite() {
        BigDecimal sum = BigDecimal.ZERO;
        for (SalesOrderLine line : items) {
            BigDecimal qty = line.getQuantity() == null ? BigDecimal.ZERO : line.getQuantity();
            BigDecimal price = line.getUnitPrice() == null ? BigDecimal.ZERO : line.getUnitPrice();
            BigDecimal amount = qty.multiply(price);
            line.setAmount(amount);
            sum = sum.add(amount);
        }
        total = sum;
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                BusinessRule.onField("customer", "Choose a customer", () -> customer != null),
                new BusinessRule("items-required", "Add at least one item",
                        () -> items != null && !items.isEmpty()),
                new BusinessRule("total-positive", "Total must be positive",
                        () -> total != null && total.signum() > 0));
    }

    @Override
    public void handlePosting(PostingContext context) {
        // See onno-posting for full register movement examples.
    }
}
```

## Tabular Section Row

```java
package com.acme.sales.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import java.math.BigDecimal;

@Getter
@Setter
public class SalesOrderLine extends TabularSectionRow {

    @Attribute(displayName = "Product", required = true)
    private Ref<Product> product;

    @Attribute(displayName = "Quantity", precision = 15, scale = 3)
    private BigDecimal quantity = BigDecimal.ONE;

    @Attribute(displayName = "Unit price", precision = 15, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Attribute(displayName = "Amount", precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;
}
```

Line rows are owned by the document. Do not create repositories for tabular section rows. If a line
must live independently, it is probably a catalog or document of its own, not a tabular section.
Give every `@TabularSection` a distinct concrete row class: Spring Data maps that class to one child
table, and startup rejects reuse across documents or sections. Structurally similar rows can extend
a shared base class.

## Status Enumeration

```java
@Enumeration(name = "OrderStatuses", title = "Order status")
public enum OrderStatus {
    @EnumLabel(value = "Draft", color = "#6B7280") DRAFT,
    @EnumLabel(value = "Confirmed", color = "#2563EB") CONFIRMED,
    @EnumLabel(value = "Fulfilled", color = "#059669") FULFILLED,
    @EnumLabel(value = "Cancelled", color = "#DC2626") CANCELLED
}
```

The enum constrains values; it does not enforce legal transitions. Recheck transition rules in the
application command/service that changes status.

## Typed Entity View

```java
@Component
public class SalesOrderView implements EntityView<SalesOrder> {
    @Override public Class<SalesOrder> entity() { return SalesOrder.class; }

    @Override
    public void list(ListSpec<SalesOrder> list) {
        list.columns(SalesOrder::getNumber, SalesOrder::getDate, SalesOrder::getCustomer,
                SalesOrder::getStatus, SalesOrder::getTotal)
            .sortBy(SalesOrder::getDate, true);
        list.filter(SalesOrder::getStatus).multiOptions();
    }

    @Override
    public void fields(EntityConfigBuilder<SalesOrder> f) {
        f.refField(SalesOrder::getCustomer).label("Customer");
        f.field(SalesOrder::getTotal).format("currency:USD").hideInForm();
        f.rowRefField(SalesOrder::getItems, SalesOrderLine::getProduct).label("Product");
        f.rowField(SalesOrder::getItems, SalesOrderLine::getQuantity).label("Quantity");
        f.rowField(SalesOrder::getItems, SalesOrderLine::getUnitPrice)
                .label("Unit price").format("currency:USD");
        f.rowField(SalesOrder::getItems, SalesOrderLine::getAmount)
                .label("Amount").format("currency:USD");
    }
}
```

## Repository

```java
package com.acme.sales.repositories;

import com.acme.sales.domain.SalesOrder;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;

@Repository
public interface SalesOrderRepository extends DocumentRepository<SalesOrder> {
}
```

`findActiveById`, `findActiveByNumber`, and `findActiveByDateBetween` are inherited. Add a custom
deletion-aware finder only for a field that actually exists. Raw inherited finders can return
deletion-marked rows.

## Runtime Verification

In a cookie-authenticated session, obtain CSRF and then exercise:

```text
POST /api/documents/SalesOrders/validate
POST /api/documents/SalesOrders
GET  /api/documents/SalesOrders/{id}
GET  /api/list/documents/SalesOrders?limit=50
PUT  /api/documents/SalesOrders/{id}
```

Writes use camelCase header/row fields and send rows under `items`. Single-record reads use
snake_case/storage keys and include inline lines. Keyset list reads return
`{rows,nextCursor,hasMore}` and intentionally omit line sections. Updating a section replaces its
rows as a whole. Verify both the computed header total and computed line amounts after create/update.

## Document Or Something Else

Use a document when the user says "create", "approve", "post", "ship", "pay", "close", "cancel", or
"audit this event". Use a catalog when the object is a reusable list item. Use a register when the
question is about balance/history, not the source event.

Examples:

- "Customer" is a catalog.
- "Sales order" is a document.
- "Order item" is a tabular section row.
- "Stock on hand" is a balance register.
- "Price by date" is an information register.

## Common Mistakes

- Putting `@TabularSection` on a catalog. Tabular sections belong to documents only.
- Reusing one concrete `TabularSectionRow` class in multiple sections. Use distinct concrete
  subclasses over a shared base row class.
- Unconditionally resetting status/date in `onFilling`. It also runs for new instances saved through
  code, so guard on null.
- Treating `number` and `date` as custom fields. They are inherited from `DocumentObject`.
- Computing totals only in posting. Totals should be visible before posting, so compute in
  `beforeWrite`.
