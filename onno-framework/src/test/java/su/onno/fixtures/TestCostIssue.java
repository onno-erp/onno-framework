package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

@Document(name = "TestCostIssues")
@Getter
@Setter
public class TestCostIssue extends DocumentObject implements Postable {

    @Attribute
    private UUID product;

    @Attribute(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal cost;

    @Override
    public void handlePosting(PostingContext context) {
        var stock = context.movements(TestCostRegister.class);
        TestCostRegister balance = stock.getBalance(Map.of("product", product))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No stock for " + product));
        BigDecimal unitCost = balance.getAmount().divide(
                balance.getQuantity(), 8, RoundingMode.HALF_UP);
        cost = unitCost.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        stock.addExpense(movement -> {
            movement.setProduct(product);
            movement.setQuantity(quantity);
            movement.setAmount(cost);
        });
    }
}
