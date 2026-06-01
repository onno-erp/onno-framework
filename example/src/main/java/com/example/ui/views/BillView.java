package com.example.ui.views;

import com.example.domain.documents.Bill;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the Bill document as a visible surface and its field order. List
 * columns fall back to the auto-generated defaults.
 */
@Component
public class BillView implements EntityView {

    @Override
    public Class<?> entity() {
        return Bill.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("client").order(0)
                .field("property").order(1)
                .field("bookingRef").order(2)
                .field("net").order(3)
                .field("ivaPercent").order(4)
                .field("iva").order(5).hideInForm()
                .field("gross").order(6).hideInForm()
                .field("comments").order(10);
    }
}
