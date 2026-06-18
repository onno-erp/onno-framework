package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Document(name = "TestReceipts")
@Getter
@Setter
public class TestReceipt extends DocumentObject implements Postable {

    @Attribute
    private UUID warehouse;

    @TabularSection(name = "items")
    private List<TestReceiptLine> items = new ArrayList<>();

    @Override
    public void handlePosting(PostingContext context) {
        var movements = context.movements(TestStockRegister.class);
        for (TestReceiptLine line : items) {
            movements.addReceipt(r -> {
                r.setProduct(line.getProduct());
                r.setWarehouse(this.warehouse);
                r.setQuantity(line.getQuantity());
            });
        }
    }
}
