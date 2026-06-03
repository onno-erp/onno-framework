package com.example.ui.views;

import com.example.domain.documents.Payment;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the Payment document as a visible surface and its field order. List
 * columns fall back to the auto-generated defaults.
 */
@Component
public class PaymentView implements EntityView {

    @Override
    public Class<?> entity() {
        return Payment.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("client").order(0)
                .field("account").order(1)
                .field("method").order(2)
                .field("bill").order(3)
                .field("amount").order(4)
                .field("notes").order(5);
    }
}
