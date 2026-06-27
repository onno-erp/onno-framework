package com.example.ui.views;

import com.example.domain.documents.StockReceipt;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * Stock receipts — receiving books from a supplier. The lines (which book, how many) are edited in
 * the document's tabular section; Post raises the {@code BookStock} balances.
 */
@Component
public class StockReceiptView implements EntityView {

    @Override
    public Class<?> entity() {
        return StockReceipt.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "date", "supplier", "posted")
                .sortBy("date", true);
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("supplier").order(0)
            .field("date").order(1).format("dd-MM-yyyy")
            .field("note").order(2).widget("textarea");
    }
}
