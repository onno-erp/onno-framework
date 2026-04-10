package com.example.domain.documents;

import com.example.domain.catalogs.Warehouse;
import com.example.domain.registers.SalesRegister;
import com.example.domain.registers.StockRegister;
import com.onec.annotations.Attribute;
import com.onec.annotations.DashboardWidget;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.annotations.UiHint;
import com.onec.annotations.UiSection;
import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.lifecycle.Postable;
import com.onec.model.DocumentObject;
import com.onec.posting.PostingContext;
import com.onec.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Document(name = "Sales Documents", tableName = "Sales", numberLength = 9)
@UiSection(value = "Sales", order = 2)
@DashboardWidget(title = "Sales Calendar", type = "calendar", order = 0, width = "1/3",
        dateField = "_date", titleField = "customer")
@DashboardWidget(title = "Recent Sales", type = "list", order = 1, width = "1/2", maxItems = 5)
@Getter
@Setter
public class Sale extends DocumentObject implements BeforeWriteHandler, Postable {

    @Attribute(length = 200)
    @UiHint(order = 0)
    private String customer;

    @Attribute
    @UiHint(order = 1)
    private Ref<Warehouse> warehouse;

    @Attribute(precision = 15, scale = 2)
    @UiHint(order = 2, visibleInForm = false)
    private BigDecimal total;

    @TabularSection(name = "items")
    private List<SaleLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        BigDecimal sum = BigDecimal.ZERO;
        for (SaleLine line : items) {
            BigDecimal qty = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ZERO;
            BigDecimal price = line.getPrice() != null ? line.getPrice() : BigDecimal.ZERO;
            BigDecimal amt = qty.multiply(price);
            line.setAmount(amt);
            sum = sum.add(amt);
        }
        this.total = sum;
    }

    @Override
    public void handlePosting(PostingContext context) {
        var stock = context.movements(StockRegister.class);
        var sales = context.movements(SalesRegister.class);

        for (SaleLine line : items) {
            // Decrease stock
            stock.addExpense(r -> {
                r.setWarehouse(warehouse);
                r.setProduct(line.getProduct());
                r.setQuantity(line.getQuantity());
            });

            // Record sale revenue
            sales.addReceipt(r -> {
                r.setProduct(line.getProduct());
                r.setQuantity(line.getQuantity());
                r.setAmount(line.getAmount());
            });
        }
    }
}
