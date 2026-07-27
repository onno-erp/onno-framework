package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Document(name = "TestCostReceipts")
@Getter
@Setter
public class TestCostReceipt extends DocumentObject implements Postable {

    @Attribute
    private UUID product;

    @Attribute(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal amount;

    @Override
    public void handlePosting(PostingContext context) {
        context.movements(TestCostRegister.class).addReceipt(movement -> {
            movement.setProduct(product);
            movement.setQuantity(quantity);
            movement.setAmount(amount);
        });
    }
}
