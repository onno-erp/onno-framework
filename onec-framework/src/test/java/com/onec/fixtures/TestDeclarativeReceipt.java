package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.lifecycle.Postable;
import com.onec.model.DocumentObject;
import com.onec.posting.PostingContext;
import com.onec.rules.BusinessRule;
import com.onec.rules.Validated;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Document(name = "TestDeclarativeReceipts")
@Getter
@Setter
public class TestDeclarativeReceipt extends DocumentObject implements Postable, Validated {

    @Attribute
    private UUID warehouse;

    @TabularSection(name = "items")
    private List<TestReceiptLine> items = new ArrayList<>();

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                new BusinessRule("warehouse-required", "Warehouse is required", () -> warehouse != null),
                new BusinessRule("items-required", "At least one item is required",
                        () -> items != null && !items.isEmpty()));
    }

    @Override
    public void handlePosting(PostingContext context) {
        var stock = context.movements(TestStockRegister.class);
        for (TestReceiptLine item : items) {
            stock.addReceipt(r -> {
                r.setProduct(item.getProduct());
                r.setWarehouse(warehouse);
                r.setQuantity(item.getQuantity());
            });
        }
    }
}
