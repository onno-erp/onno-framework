package com.example.ui.views;

import com.example.domain.registers.BookSales;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Field hints for the {@link BookSales} turnover register. A register has no served entity surface of
 * its own (it renders through the register report listed in the nav), but its resource columns still
 * pick up format hints declared here — so Revenue shows as currency in the Reports view instead of a
 * bare, scale-inconsistent number. {@code list()} is intentionally left default; only {@code fields()}
 * (the format hints) applies to a register.
 */
@Component
public class BookSalesView implements EntityView {

    @Override
    public Class<?> entity() {
        return BookSales.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("revenue").format("currency:USD")
            // Date-only movement timestamp in the register report (unhinted columns get the locale default).
            .field("period").format("dd-MM-yyyy");
    }
}
