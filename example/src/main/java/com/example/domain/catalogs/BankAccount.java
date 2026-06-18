package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A bank account money is collected into — a {@code @Catalog}. A {@link com.example.domain.documents.Payment}
 * names the account it landed in, and that posts a movement into the Bank Balance register keyed on
 * this account. Finance-only, by {@code @AccessControl}: both reading and writing require the FINANCE
 * role (ADMIN is always a superuser).
 */
@Catalog(name = "Bank Accounts", codeLength = 12, codePrefix = "BA-", context = "Rentals")
@AccessControl(readRoles = {"FINANCE"}, writeRoles = {"FINANCE"})
@Getter
@Setter
public class BankAccount extends CatalogObject {

    @Attribute(displayName = "Nominee", length = 200, required = true)
    private String nominee;

    @Attribute(displayName = "IBAN", length = 34, required = true)
    private String iban;

    @Attribute(displayName = "BIC / SWIFT", length = 12)
    private String bic;

    @Attribute(length = 100)
    private String bankName;
}
