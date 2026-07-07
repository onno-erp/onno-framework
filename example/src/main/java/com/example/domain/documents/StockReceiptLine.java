package com.example.domain.documents;

import com.example.domain.catalogs.Book;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One line of a {@link StockReceipt}: which book, and how many copies received. */
@Getter
@Setter
public class StockReceiptLine extends TabularSectionRow {

    @Attribute(displayName = "Book")
    private Ref<Book> book;

    @Attribute(displayName = "Quantity", precision = 12, scale = 0)
    private BigDecimal quantity = BigDecimal.ONE;
}
