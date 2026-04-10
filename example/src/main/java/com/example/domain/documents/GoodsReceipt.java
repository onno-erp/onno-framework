package com.example.domain.documents;

import com.example.domain.catalogs.Warehouse;
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

@Document(name = "Inbound Goods", tableName = "Goods Receipts", numberLength = 9)
@UiSection(value = "Warehouse", order = 1)
@DashboardWidget(title = "Recent Receipts", type = "list", order = 2, width = "1/2", maxItems = 5)
@Getter
@Setter
public class GoodsReceipt extends DocumentObject implements BeforeWriteHandler, Postable {

    @Attribute(length = 200)
    @UiHint(order = 0)
    private String supplier;

    @Attribute
    @UiHint(order = 1)
    private Ref<Warehouse> warehouse;

    @Attribute(precision = 15, scale = 2)
    @UiHint(order = 2, visibleInForm = false)
    private BigDecimal total;

    @TabularSection(name = "items")
    private List<GoodsReceiptLine> items = new ArrayList<>();

    @Override
    public void beforeWrite() {
        BigDecimal sum = BigDecimal.ZERO;
        for (GoodsReceiptLine line : items) {
            BigDecimal qty = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ZERO;
            BigDecimal uc = line.getUnitCost() != null ? line.getUnitCost() : BigDecimal.ZERO;
            BigDecimal cost = qty.multiply(uc);
            line.setTotalCost(cost);
            sum = sum.add(cost);
        }
        this.total = sum;
    }

    @Override
    public void handlePosting(PostingContext context) {
        var stock = context.movements(StockRegister.class);
        for (GoodsReceiptLine line : items) {
            stock.addReceipt(r -> {
                r.setWarehouse(warehouse);
                r.setProduct(line.getProduct());
                r.setQuantity(line.getQuantity());
            });
        }
    }
}
