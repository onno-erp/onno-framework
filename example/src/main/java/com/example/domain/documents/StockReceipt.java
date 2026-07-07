package com.example.domain.documents;

import com.example.domain.catalogs.Supplier;
import com.example.domain.registers.BookStock;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Receiving books into stock from a supplier — the procurement side. On posting, each line writes a
 * receipt into {@link BookStock}, raising the copies on hand. The counterpart to an
 * {@link Order}, which takes copies back out.
 */
@Document(name = "Stock Receipts", title = "Stock receipt", numberPrefix = "SR-", context = "Inventory")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class StockReceipt extends DocumentObject implements OnFillingHandler, Postable {

    @Attribute(displayName = "Supplier", required = true)
    private Ref<Supplier> supplier;

    @Attribute(displayName = "Note", length = 500)
    private String note;

    @TabularSection(name = "lines")
    private List<StockReceiptLine> lines = new ArrayList<>();

    @Override
    public void onFilling() {
        // Idempotent: onFilling runs on every save of a new entity, so only fill a missing date —
        // never overwrite one already set.
        if (getDate() == null) {
            setDate(LocalDateTime.now());
        }
    }

    @Override
    public void handlePosting(PostingContext context) {
        var stock = context.movements(BookStock.class);
        for (StockReceiptLine line : lines) {
            if (line.getBook() == null || line.getQuantity() == null) {
                continue;
            }
            stock.addReceipt(r -> {
                r.setBook(line.getBook());
                r.setQuantity(line.getQuantity());
            });
        }
    }
}
