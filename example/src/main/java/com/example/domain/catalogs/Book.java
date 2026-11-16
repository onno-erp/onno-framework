package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A title the shop sells — the {@code Product} of the bookstore. Master data: edited at runtime,
 * referenced by an {@link com.example.domain.documents.OrderLine} (what's sold) and a
 * {@link com.example.domain.documents.StockReceiptLine} (what's received). Its running stock balance
 * lives in {@link com.example.domain.registers.BookStock}.
 *
 * <p>A {@code Ref<T>} is a typed pointer to another entity stored as a UUID — here the
 * {@link BookCategory} and {@link Supplier} the book belongs to. The human label is the inherited
 * {@code description} (the title).</p>
 */
@Catalog(name = "Books", codePrefix = "B-", context = "Catalog")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class Book extends CatalogObject {

    @Attribute(displayName = "Author", length = 200)
    private String author;

    @Attribute(displayName = "ISBN", length = 20)
    private String isbn;

    @Attribute(displayName = "Category")
    private Ref<BookCategory> category;

    @Attribute(displayName = "Supplier")
    private Ref<Supplier> supplier;

    @Attribute(displayName = "Price", precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    /** Cover image URL (an upload to {@code POST /api/media}); the Shelf list view
     *  ({@code src/main/ui/widgets/BookTiles.tsx}) draws it as the book's tile. */
    @Attribute(displayName = "Cover", length = 500)
    private String coverUrl;
}
