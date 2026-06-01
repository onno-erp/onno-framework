package com.example.domain.documents;

import com.example.domain.catalogs.BankAccount;
import com.example.domain.catalogs.Client;
import com.example.domain.enumerations.PaymentMethod;
import com.example.domain.registers.BankBalanceRegister;
import com.example.domain.registers.ReceivablesRegister;
import com.onec.annotations.AccessControl;
import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.lifecycle.Postable;
import com.onec.model.DocumentObject;
import com.onec.posting.PostingContext;
import com.onec.rules.BusinessRule;
import com.onec.rules.Validated;
import com.onec.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Document(name = "Payments", numberPrefix = "PMT-", numberLength = 14, context = "Rentals")
@AccessControl(readRoles = {"ADMIN", "RENTALS", "FINANCE"}, writeRoles = {"ADMIN", "FINANCE"})
@Getter
@Setter
public class Payment extends DocumentObject implements Postable, Validated {

    @Attribute(required = true)
    private Ref<Client> client;

    @Attribute
    private Ref<BankAccount> account;

    @Attribute
    private PaymentMethod method;

    @Attribute(displayName = "Bill ref")
    private UUID billRef;

    @Attribute(precision = 14, scale = 2, required = true)
    private BigDecimal amount;

    @Attribute(length = 500)
    private String notes;

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                new BusinessRule("client-required", "Client is required", () -> client != null),
                new BusinessRule("amount-positive", "Amount must be positive",
                        () -> amount != null && amount.signum() > 0));
    }

    @Override
    public void handlePosting(PostingContext context) {
        // Settle the receivable: payment reduces what the client owes.
        var receivables = context.movements(ReceivablesRegister.class);
        receivables.addExpense(r -> {
            r.setClient(client);
            r.setAmount(amount);
        });

        if (account != null) {
            var bank = context.movements(BankBalanceRegister.class);
            bank.addReceipt(r -> {
                r.setAccount(account);
                r.setAmount(amount);
            });
        }
    }
}
