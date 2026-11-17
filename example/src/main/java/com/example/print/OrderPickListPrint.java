package com.example.print;

import com.example.domain.documents.Order;
import su.onno.print.PrintForm;
import su.onno.print.PrintFormSpec;
import su.onno.print.TemplateModel;

import org.springframework.stereotype.Component;

/**
 * An Excel print form: {@code src/main/resources/print/pick-list.xlsx}. Numbers and dates land in
 * real typed cells formatted by the template, the line row repeats per item, and the template's
 * {@code SUM} over that row stretches to cover every inserted line.
 */
@Component
public class OrderPickListPrint extends PrintForm<Order> {

    public OrderPickListPrint() {
        super("pick-list", Order.class);
    }

    @Override
    protected void configure(PrintFormSpec spec) {
        spec.label("Pick list (Excel)");
    }

    @Override
    protected void fill(Order order, TemplateModel model) {
        model.text("number", order.getNumber())
                .date("date", order.getDate())
                .text("customer", order.getCustomer())
                .text("assignee", order.getAssignedTo())
                .table("items", order.getItems(), (row, line) -> row
                        .text("book", line.getBook())
                        .number("qty", line.getQuantity())
                        .number("price", line.getUnitPrice())
                        .number("amount", line.getAmount()));
    }
}
