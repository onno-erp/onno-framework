package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.RegisterMovement;
import com.onec.annotations.TabularSection;
import com.onec.lifecycle.Postable;
import com.onec.model.DocumentObject;
import com.onec.model.MovementType;
import com.onec.posting.PostingContext;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Document(name = "TestReceipts")
@RegisterMovement(register = TestStockRegister.class)
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
            var record = movements.add();
            record.setProduct(line.getProduct());
            record.setWarehouse(this.warehouse);
            record.setQuantity(line.getQuantity());
            record.setMovementType(MovementType.RECEIPT);
        }
    }
}
