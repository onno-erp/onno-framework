package com.example.ui.pages;

import com.example.domain.registers.BookSales;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * Overrides a <b>register</b> route. The "Book sales" register normally renders its raw movement log
 * at {@code /registers/book_sales}; this authored {@code Page} at the same route replaces it with a
 * curated report — the register's turnover resources aggregated into KPI tiles.
 *
 * <p>Demonstrates that register routes are page-overridable too (the same mechanism as catalog/document
 * list routes). Note: {@code b.list(...)} embeds a catalog/document list but not a register's
 * movement log yet, so a register page composes register-backed widgets (count/metric over the
 * turnover resources) rather than re-embedding the log. Delete this bean and the route falls back to
 * the default register surface.</p>
 */
@Component
public class BookSalesReportPage implements Page {

    @Override
    public String route() {
        return "/registers/book_sales";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Sales report");
        b.subtitle("Turnover across the Book Sales register");

        b.widget("Total revenue").type("count").width("1/2").order(0).register(BookSales.class)
                .config("metric", "sum").config("metricField", "revenue")
                .config("currency", "USD")
                .hint("Sum of the register's Revenue resource.");

        b.widget("Units sold").type("count").width("1/2").order(1).register(BookSales.class)
                .config("metric", "sum").config("metricField", "quantity")
                .hint("Sum of the register's Quantity resource.");

        b.text("A page authored at a register route — the curated report replaces the raw movement log.");
    }
}
