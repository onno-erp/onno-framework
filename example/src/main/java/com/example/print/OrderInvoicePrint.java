package com.example.print;

import com.example.domain.constants.StoreName;
import com.example.domain.documents.Order;
import su.onno.print.PrintForm;
import su.onno.print.PrintFormSpec;
import su.onno.print.TemplateModel;
import su.onno.repository.ConstantManager;

import org.springframework.stereotype.Component;

/**
 * A Word print form: {@code src/main/resources/print/invoice.docx} filled from an {@link Order}. The
 * template holds {@code {{placeholders}}}; the row containing {@code {{items.book}}} repeats once per
 * order line. Registering the bean is all it takes — the order's record menu gets "Invoice (Word)".
 */
@Component
public class OrderInvoicePrint extends PrintForm<Order> {

    private final ConstantManager constants;

    public OrderInvoicePrint(ConstantManager constants) {
        super("invoice", Order.class);
        this.constants = constants;
    }

    @Override
    protected void configure(PrintFormSpec spec) {
        spec.label("Invoice (Word)");
    }

    @Override
    protected void fill(Order order, TemplateModel model) {
        // An unsaved constant reads as null; fall back to the class's default value.
        String store = constants.get(StoreName.class);
        model.text("store", store != null ? store : new StoreName().getValue())
                .text("number", order.getNumber())
                .date("date", order.getDate(), "d MMMM yyyy")
                .text("customer", order.getCustomer())
                .text("status", order.getStatus())
                .number("total", order.getTotal(), "#,##0.00")
                .text("note", order.getNote())
                .table("items", order.getItems(), (row, line) -> row
                        .text("book", line.getBook())
                        .number("qty", line.getQuantity())
                        .number("price", line.getUnitPrice(), "#,##0.00")
                        .number("amount", line.getAmount(), "#,##0.00"));
    }
}
