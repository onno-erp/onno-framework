# Documents And Lines Examples

## Table Of Contents

- Complete Sales Order
- Tabular Section Row
- Repository
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

@Document(name = "Sales Orders", title = "Sales orders", numberPrefix = "SO-", context = "Sales")
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

## Repository

```java
package com.acme.sales.repositories;

import com.acme.sales.domain.SalesOrder;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SalesOrderRepository extends DocumentRepository<SalesOrder> {
    List<SalesOrder> findActiveByDateBetween(LocalDateTime from, LocalDateTime to);
    List<SalesOrder> findByExternalNumberAndDeletionMarkFalse(String externalNumber);
}
```

Business logic should use active finders. Raw inherited finders can return deletion-marked rows.

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
- Unconditionally resetting status/date in `onFilling`. It also runs for new instances saved through
  code, so guard on null.
- Treating `number` and `date` as custom fields. They are inherited from `DocumentObject`.
- Computing totals only in posting. Totals should be visible before posting, so compute in
  `beforeWrite`.
