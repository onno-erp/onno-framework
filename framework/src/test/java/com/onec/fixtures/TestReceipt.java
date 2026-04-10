package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;
import com.onec.lifecycle.Postable;
import com.onec.model.DocumentObject;
import com.onec.annotations.HandlePosting;
import com.onec.model.MovementType;
import com.onec.posting.RegisterMovementCollection;

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

    @HandlePosting
    public void handlePosting(RegisterMovementCollection<TestStockRegister> movements) {
        for (TestReceiptLine line : items) {
            var record = movements.add();
            record.setProduct(line.getProduct());
            record.setWarehouse(this.warehouse);
            record.setQuantity(line.getQuantity());
            record.setMovementType(MovementType.RECEIPT);
        }
    }
}
