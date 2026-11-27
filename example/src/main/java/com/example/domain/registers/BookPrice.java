package com.example.domain.registers;

import com.example.domain.catalogs.Book;
import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.annotations.Resource;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;
import su.onno.types.Ref;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Demonstration of dated values, independent of the price paid on an order. */
@InformationRegister(name = "Book Prices", title = "Book prices", periodicity = Periodicity.DAY, context = "Catalog")
@Getter
@Setter
public class BookPrice extends InformationRecord {
    @Dimension(displayName = "Book")
    private Ref<Book> book;

    @Resource(displayName = "Price", precision = 15, scale = 2)
    private BigDecimal price;
}
