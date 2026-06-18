package com.example.domain.registers;

import com.example.domain.catalogs.BankAccount;
import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Cash on hand per bank account — a {@link AccumulationType#BALANCE} register (a running total, like
 * Receivables). {@link com.example.domain.documents.Payment} posts {@code addReceipt} into it when a
 * payment names an account, so the balance per {@code account} reflects money actually collected.
 */
@AccumulationRegister(name = "Bank Balance", type = AccumulationType.BALANCE, context = "Rentals")
@AccessControl(readRoles = {"FINANCE"})
@Getter
@Setter
public class BankBalanceRegister extends AccumulationRecord {

    @Dimension
    private Ref<BankAccount> account;

    @Resource(precision = 14, scale = 2)
    private BigDecimal amount;
}
