package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * A many-to-many join: a supplier offers a book at a purchase price. A book can be offered by several
 * suppliers and a supplier offers many books, so the link is its own catalog with a reference to each
 * side plus what the link carries. The UI edits it from both sides as a relation (see the
 * {@code relations} customization in {@code entities/Suppliers.ts} and {@code entities/Books.ts}).
 */
@Catalog(name = "Supplier Offers", title = "Supplier offers", codePrefix = "OFR-", context = "Catalog")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class SupplierOffer extends CatalogObject {

    @Attribute(displayName = "Supplier", required = true)
    private Ref<Supplier> supplier;

    @Attribute(displayName = "Book", required = true)
    private Ref<Book> book;

    @Attribute(displayName = "Purchase price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;
}
