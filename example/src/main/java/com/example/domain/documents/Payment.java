package com.example.domain.documents;

import com.example.domain.catalogs.BankAccount;
import com.example.domain.catalogs.Client;
import com.example.domain.enumerations.PaymentMethod;
import com.example.domain.registers.BankBalanceRegister;
import com.example.domain.registers.ReceivablesRegister;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Money received from a client — the document that closes the bill→pay loop. On posting it moves two
 * {@code BALANCE} registers in tandem: it lowers {@link ReceivablesRegister} ({@code addExpense} — the
 * client owes less) and, when an account is named, raises {@link BankBalanceRegister}
 * ({@code addReceipt} — cash collected). See {@link #handlePosting}. Unlike {@link Bill} and
 * {@link Booking} it needs no {@code beforeWrite} — there are no derived fields to compute.
 */
@Document(name = "Payments", numberPrefix = "PMT-", numberLength = 14, context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"}, writeRoles = {"FINANCE"})
@Getter
@Setter
public class Payment extends DocumentObject implements Postable, Validated {

    @Attribute(required = true)
    private Ref<Client> client;

    @Attribute
    private Ref<BankAccount> account;

    @Attribute
    private PaymentMethod method;

    // A document → document reference: the bill this payment settles. Rendered as a
    // searchable document ref picker in the UI (resolves to the bill's number).
    @Attribute(displayName = "Bill")
    private Ref<Bill> bill;

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
