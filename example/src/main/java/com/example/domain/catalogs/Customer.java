package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * Someone who buys books from the shop. Referenced by a customer {@link com.example.domain.documents.Order}.
 * The human label is the inherited {@code description} (the customer's name).
 */
@Catalog(name = "Customers", title = "Customer", codePrefix = "CU-", context = "Sales")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class Customer extends CatalogObject {

    @Attribute(displayName = "Email", length = 200, email = true)
    private String email;

    @Attribute(displayName = "Phone", length = 50)
    private String phone;

    @Attribute(displayName = "Address", length = 500)
    private String address;
}
