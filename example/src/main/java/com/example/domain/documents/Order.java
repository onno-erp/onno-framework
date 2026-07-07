package com.example.domain.documents;

import com.example.domain.catalogs.Customer;
import com.example.domain.catalogs.Employee;
import com.example.domain.enumerations.OrderStatus;
import com.example.domain.registers.BookSales;
import com.example.domain.registers.BookStock;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;
import su.onno.ui.notifications.AssigneeField;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's book order — the central {@code @Document}. A document is a dated, numbered business
 * event ({@code O-…} here); the framework supplies {@code number} and {@code date}. It wires the
 * four lifecycle interfaces a document typically needs:
 *
 * <ul>
 *   <li>{@link OnFillingHandler} — pre-fill a new order ({@code date} = now) so the New form opens
 *       populated rather than blank (the {@code status} default is the {@code NEW} field initializer).</li>
 *   <li>{@link BeforeWriteHandler} — on every save, compute each line's {@code amount} and the order
 *       {@code total}, and the derived {@code open} flag — never keyed by hand.</li>
 *   <li>{@link Validated} — invariants checked before write and before posting.</li>
 *   <li>{@code Postable} (via {@link #handlePosting}) — on posting, take the books out of
 *       {@link BookStock} (an expense the BALANCE register refuses if stock is short) and record the
 *       sale in {@link BookSales}. A {@code CANCELLED} order posts nothing.</li>
 * </ul>
 *
 * <p>{@code items} is a {@code @TabularSection}: repeated {@link OrderLine} rows stored with the
 * order. {@code @AccessControl} lets any MANAGER read and write orders.</p>
 */
@Document(name = "Orders", title = "Order", numberPrefix = "O-", context = "Sales")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class Order extends DocumentObject
        implements OnFillingHandler, BeforeWriteHandler, Validated, su.onno.lifecycle.Postable {

    @Attribute(displayName = "Customer", required = true)
    private Ref<Customer> customer;

    @Attribute(displayName = "Status")
    private OrderStatus status = OrderStatus.NEW;

    // @AssigneeField makes the built-in assignment producer notify the employee whenever an order is
    // assigned (or re-assigned) to them — they get a top-right notification the moment it's set.
    @AssigneeField
    @Attribute(displayName = "Assigned to")
    private Ref<Employee> assignedTo;

    @Attribute(displayName = "Total", precision = 14, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Attribute(displayName = "Note", length = 1000)
    private String note;

    /** Derived: the order is still open (not yet completed or cancelled). A real boolean column so the
     *  dashboard can count open orders with a plain {@code open = true} filter — count tiles aggregate
     *  one column and take no enum predicate. Kept in sync in {@link #beforeWrite()}. */
    @Attribute(displayName = "Open")
    private Boolean open;

    @TabularSection(name = "items")
    private List<OrderLine> items = new ArrayList<>();

    @Override
    public void onFilling() {
        // Pre-fill the order date so a new order isn't dateless. onFilling runs on every save of a
        // *new* entity (not only the blank-form pre-fill), so it must be idempotent — guard on null
        // or it would overwrite an explicitly-set date. The status default is the field initializer
        // above ({@code NEW}); setting it here unconditionally would clobber a seeded/imported status.
        if (getDate() == null) {
            setDate(LocalDateTime.now());
        }
    }

    @Override
    public void beforeWrite() {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderLine line : items) {
            BigDecimal qty = line.getQuantity() == null ? BigDecimal.ZERO : line.getQuantity();
            BigDecimal price = line.getUnitPrice() == null ? BigDecimal.ZERO : line.getUnitPrice();
            BigDecimal amount = qty.multiply(price);
            line.setAmount(amount);
            sum = sum.add(amount);
        }
        this.total = sum;
        this.open = status != OrderStatus.COMPLETED && status != OrderStatus.CANCELLED;
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                new BusinessRule("customer-required", "Choose a customer", () -> customer != null),
                // A real order has at least one line — but allow a cancelled one to carry none.
                new BusinessRule("items-required", "Add at least one book",
                        () -> status == OrderStatus.CANCELLED || (items != null && !items.isEmpty())));
    }

    @Override
    public void handlePosting(PostingContext context) {
        if (status == OrderStatus.CANCELLED) {
            return;
        }
        var stock = context.movements(BookStock.class);
        var sales = context.movements(BookSales.class);
        for (OrderLine line : items) {
            if (line.getBook() == null || line.getQuantity() == null) {
                continue;
            }
            // Take the copies out of stock — the BALANCE register rejects the post if it would go
            // negative, so an oversold order can't be confirmed.
            stock.addExpense(r -> {
                r.setBook(line.getBook());
                r.setQuantity(line.getQuantity());
            });
            // Record the sale (turnover): units and revenue, by book and seller.
            sales.addReceipt(r -> {
                r.setBook(line.getBook());
                r.setSoldBy(assignedTo);
                r.setQuantity(line.getQuantity());
                r.setRevenue(line.getAmount());
            });
        }
    }
}
