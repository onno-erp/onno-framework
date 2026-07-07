package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A publisher/wholesaler the shop buys books from. Referenced by {@link Book} and by a
 * {@link com.example.domain.documents.StockReceipt}. The human label is the inherited
 * {@code description} (the supplier's name).
 */
@Catalog(name = "Suppliers", title = "Supplier", codePrefix = "SUP-", context = "Catalog")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class Supplier extends CatalogObject {

    @Attribute(displayName = "Email", length = 200, email = true)
    private String email;

    @Attribute(displayName = "Phone", length = 50)
    private String phone;

    @Attribute(displayName = "Note", length = 500)
    private String note;
}
