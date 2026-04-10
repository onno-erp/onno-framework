package com.example.domain.documents;

import com.example.domain.registers.SalesRegister;
import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.lifecycle.Postable;
import com.onec.model.DocumentObject;
import com.onec.posting.PostingContext;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Document(name = "Invoices", numberLength = 11)
@Getter
@Setter
public class Invoice extends DocumentObject implements BeforeWriteHandler, Postable {

    @Attribute(length = 200)
    private String customer;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal total;

    @TabularSection(name = "items")
    private List<InvoiceLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        BigDecimal sum = BigDecimal.ZERO;
        for (InvoiceLine line : items) {
            BigDecimal amount = line.getQuantity().multiply(line.getPrice());
            line.setAmount(amount);
            sum = sum.add(amount);
        }
        this.total = sum;
    }

    @Override
    public void handlePosting(PostingContext context) {
        var sales = context.movements(SalesRegister.class);
        for (InvoiceLine line : items) {
            sales.addReceipt(r -> {
                r.setProduct(line.getProduct());
                r.setQuantity(line.getQuantity());
                r.setAmount(line.getAmount());
            });
        }
    }
}
