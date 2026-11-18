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

/** Writes one EXPENSE movement per line to {@link TestStockRegister}: the counterpart of {@link TestReceipt}. */
@Document(name = "TestStockIssues")
@Getter
@Setter
public class TestStockIssue extends DocumentObject implements Postable {

    @Attribute
    private UUID warehouse;

    @TabularSection(name = "items")
    private List<TestStockIssueLine> items = new ArrayList<>();

    @Override
    public void handlePosting(PostingContext context) {
        var movements = context.movements(TestStockRegister.class);
        for (TestStockIssueLine line : items) {
            movements.addExpense(r -> {
                r.setProduct(line.getProduct());
                r.setWarehouse(this.warehouse);
                r.setQuantity(line.getQuantity());
            });
        }
    }
}
