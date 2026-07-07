package com.example.domain.documents;

import com.example.domain.catalogs.Book;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One line of a customer {@link Order}: a book, a quantity, and the unit price charged. The
 * {@code amount} (qty × price) is computed by {@link Order#beforeWrite()} so it's never keyed by
 * hand. A {@code @TabularSection} row {@code extends TabularSectionRow}; the framework stores it in
 * a child table linked to the order.
 */
@Getter
@Setter
public class OrderLine extends TabularSectionRow {

    @Attribute(displayName = "Book")
    private Ref<Book> book;

    @Attribute(displayName = "Quantity", precision = 12, scale = 0)
    private BigDecimal quantity = BigDecimal.ONE;

    @Attribute(displayName = "Unit price", precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Attribute(displayName = "Amount", precision = 14, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;
}
