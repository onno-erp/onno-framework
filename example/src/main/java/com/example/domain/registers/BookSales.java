package com.example.domain.registers;

import com.example.domain.catalogs.Book;
import com.example.domain.catalogs.Employee;
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
 * Sales activity — a {@link AccumulationType#TURNOVER} register: it sums what happened over a period
 * (copies sold, revenue taken), not a running balance. A customer
 * {@link com.example.domain.documents.Order} writes one receipt per line on posting. Sliced by book
 * and by the employee who sold it, so the dashboard and the Reports nav entry can show
 * revenue/units over time and by seller.
 */
@AccumulationRegister(name = "Book Sales", title = "Book sales", type = AccumulationType.TURNOVER,
        context = "Sales")
@AccessControl(readRoles = {"MANAGER"})
@Getter
@Setter
public class BookSales extends AccumulationRecord {

    @Dimension(displayName = "Book")
    private Ref<Book> book;

    @Dimension(displayName = "Sold by")
    private Ref<Employee> soldBy;

    @Resource(displayName = "Quantity", precision = 12, scale = 0)
    private BigDecimal quantity;

    @Resource(displayName = "Revenue", precision = 14, scale = 2)
    private BigDecimal revenue;
}
