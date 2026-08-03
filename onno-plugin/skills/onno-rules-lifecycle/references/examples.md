# Rules And Lifecycle Examples

## Table Of Contents

- Defaults With OnFillingHandler
- Derived Fields With BeforeWriteHandler
- Business Rules
- Reusable Rule Factory
- Path Matrix
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

Field initializers populate the initially rendered blank New form. `onFilling()` runs during
create/save and live create validation. Guard on null; do not overwrite values set by importers,
seeders, tests, or the form.

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

## Reusable Rule Factory

There is no framework `BusinessRuleSet` type. Share rules with ordinary Java:

```java
final class InvoiceRules {
    private InvoiceRules() {}

    static List<BusinessRule> rulesFor(Invoice invoice) {
        return List.of(
                BusinessRule.onField("customer", "Choose a customer",
                        () -> invoice.getCustomer() != null),
                new BusinessRule("items-required", "Add at least one line",
                        () -> invoice.getItems() != null && !invoice.getItems().isEmpty()));
    }
}
```

`Validated.rules()` returns `InvoiceRules.rulesFor(this)`. Validation collects all failures.

## Path Matrix

| Path | Lifecycle |
| --- | --- |
| repository insert | id → `onFilling` → number → `beforeWrite` → validation → write → `afterWrite` |
| UI/API/import/MCP create | number/date → entity → `onFilling` → `beforeWrite` → validation → write → `afterWrite` → `EntityChangedEvent` |
| update | create sequence without `onFilling` |
| post/preview | `beforeWrite` → `beforePost` → rules → `handlePosting` |

`AfterWriteHandler` runs after successful repository and generic-command persistence, but it is not
a universal after-commit hook. Validation previews never call it.

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

Normal posting invokes `handlePosting` to stage movements before transactional persistence. Preview
and chronological restoration may replay it. External calls belong after commit via events/outbox.

## Delete Guard

```java
public class Employee extends CatalogObject implements BeforeDeleteHandler {
    private boolean systemUser;

    @Override
    public void beforeDelete() {
        if (systemUser) {
            throw new ValidationException("System users cannot be deleted");
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
- An event listener is after-the-fact and cannot veto a write. Cross-record pre-write validation
  belongs in an application command/service, a command-integrated validator, or a deliberate
  application-context bridge from the hook. Use events only for side effects after success.
- Direct `PostingService.post` rejects an already-posted document. Use atomic `repost` to
  intentionally recalculate; generated REST/MCP post commands select it for an already-posted row.
