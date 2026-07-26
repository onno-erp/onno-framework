package com.example.ui.views;

import com.example.domain.documents.StockReceipt;
import com.example.domain.documents.StockReceiptLine;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * Stock receipts — receiving books from a supplier. The lines (which book, how many) are edited in
 * the document's tabular section; Post raises the {@code BookStock} balances.
 */
@Component
public class StockReceiptView implements EntityView<StockReceipt> {

    @Override
    public Class<StockReceipt> entity() {
        return StockReceipt.class;
    }

    @Override
    public void list(ListSpec<StockReceipt> list) {
        list.columns(StockReceipt::getNumber, StockReceipt::getDate,
                        StockReceipt::getSupplier, StockReceipt::isPosted)
                .sortBy(StockReceipt::getDate, true)
                // Receiving log views: per-supplier or bucketed by day/month.
                .groupable(StockReceipt::getSupplier, StockReceipt::getDate);
        list.filter(StockReceipt::getDate).label("Received").dateRange();
        list.filter(StockReceipt::getNote).label("Note").contains();
    }

    @Override
    public void fields(EntityConfigBuilder<StockReceipt> f) {
        f.field(StockReceipt::getSupplier).order(0)
            .field(StockReceipt::getDate).order(1).format("dd-MM-yyyy")
            .field(StockReceipt::getNote).order(2).widget("textarea");
        // Cascade: once the supplier is picked, the line's book picker only offers that
        // supplier's titles (and re-clears if the supplier changes).
        f.rowField(StockReceipt::getLines, StockReceiptLine::getBook)
                .refFilter("supplier = ${supplier}");
    }
}
