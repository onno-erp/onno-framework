# Rules And Lifecycle Examples

## Table Of Contents

- Defaults With OnFillingHandler
- Derived Fields With BeforeWriteHandler
- Business Rules
- Spring Services And Events
- Delete Guard
- Gotchas

## Defaults With OnFillingHandler

```java
public class Invoice extends DocumentObject implements OnFillingHandler {
    @Attribute(displayName = "Status")
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Override
    public void onFilling() {
        if (getDate() == null) {
            setDate(LocalDateTime.now());
        }
        if (status == null) {
            status = InvoiceStatus.DRAFT;
        }
    }
}
```

`onFilling()` runs for the generic blank New form and for any new entity saved through the repository
path. Guard on null. Do not overwrite values set by importers, seeders, or tests.

## Derived Fields With BeforeWriteHandler

```java
public class Invoice extends DocumentObject implements BeforeWriteHandler {
    @Attribute(precision = 15, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @TabularSection(name = "items")
    private List<InvoiceLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        total = BigDecimal.ZERO;
        for (InvoiceLine line : items) {
            BigDecimal amount = nvl(line.getQuantity()).multiply(nvl(line.getPrice()));
            line.setAmount(amount);
            total = total.add(amount);
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
```

Use `BeforeWriteHandler` for data that should be correct before posting and visible on drafts.

## Business Rules

```java
public class Invoice extends DocumentObject implements Validated {
    @Attribute
    private Ref<Customer> customer;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal total;

    @TabularSection(name = "items")
    private List<InvoiceLine> items = new ArrayList<>();

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                BusinessRule.onField("customer", "Choose a customer", () -> customer != null),
                new BusinessRule("items-required", "Add at least one line",
                        () -> items != null && !items.isEmpty()),
                new BusinessRule("total-positive", "Total must be positive",
                        () -> total != null && total.signum() > 0));
    }
}
```

Rules run before write and before posting. Give stable names and user-facing messages.

## Spring Services And Events

Do not inject a service into a domain object. For side effects after posting, listen to framework
events from a Spring bean:

```java
@Component
public class InvoicePostedListener {
    private final ExternalBillingClient billing;

    public InvoicePostedListener(ExternalBillingClient billing) {
        this.billing = billing;
    }

    @EventListener
    public void onPosted(DocumentPostedEvent event) {
        if (event.document() instanceof Invoice invoice) {
            billing.submit(invoice);
        }
    }
}
```

`handlePosting` runs inside the posting transaction and should only create movements. External calls
belong after commit via events.

## Delete Guard

```java
public class Employee extends CatalogObject implements BeforeDeleteHandler {
    private boolean systemUser;

    @Override
    public void beforeDelete() {
        if (systemUser) {
            throw new IllegalStateException("System users cannot be deleted");
        }
    }
}
```

Deletion is soft, but delete hooks still matter for business invariants.

## Gotchas

- Entity hooks have no Spring DI.
- `AfterPostHandler` exists but has no Spring DI; prefer `DocumentPostedEvent`.
- `onFilling()` must be idempotent.
- `BeforeWriteHandler` runs before save and before post.
- Cross-record validation usually belongs in a Spring service or event listener, not a domain hook,
  unless you deliberately use an application-context bridge.
