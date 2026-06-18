package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;

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
