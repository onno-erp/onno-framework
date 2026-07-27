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

@Document(name = "TestWithdrawals")
@Getter
@Setter
public class TestWithdrawal extends DocumentObject implements Postable {

    @Attribute
    private UUID account;

    @Attribute
    private BigDecimal amount;

    @Override
    public void handlePosting(PostingContext context) {
        context.movements(TestOverdraftRegister.class).addExpense(movement -> {
            movement.setAccount(account);
            movement.setAmount(amount);
        });
    }
}
