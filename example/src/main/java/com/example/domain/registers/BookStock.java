package com.example.domain.registers;

import com.example.domain.catalogs.Book;
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
 * Copies on hand per book — a {@link AccumulationType#BALANCE} register (a running total, not period
 * activity). A {@link com.example.domain.documents.StockReceipt} adds copies (receipt); a customer
 * {@link com.example.domain.documents.Order} removes them (expense) when posted.
 *
 * <p>Because it's a BALANCE register, the posting engine <b>refuses any movement that would drive a
 * balance negative</b> — so an order for more copies than are in stock cannot be posted. That
 * guarantee is free: it comes from declaring the register {@code BALANCE}, not from hand-written
 * checks. A {@code @Dimension} is what you slice by (the book); a {@code @Resource} is the number
 * that accumulates (quantity).</p>
 */
@AccumulationRegister(name = "Book Stock", title = "Book stock", type = AccumulationType.BALANCE,
        context = "Inventory")
@AccessControl(readRoles = {"MANAGER"})
@Getter
@Setter
public class BookStock extends AccumulationRecord {

    @Dimension(displayName = "Book")
    private Ref<Book> book;

    @Resource(displayName = "Quantity", precision = 12, scale = 0)
    private BigDecimal quantity;
}
