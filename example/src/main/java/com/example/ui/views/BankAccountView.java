package com.example.ui.views;

import com.example.domain.catalogs.BankAccount;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the BankAccount catalog as a visible surface and its field order. List
 * columns fall back to the auto-generated defaults.
 */
@Component
public class BankAccountView implements EntityView {

    @Override
    public Class<?> entity() {
        return BankAccount.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("nominee").order(0)
                .field("iban").order(1)
                .field("bic").order(2)
                .field("bankName").order(3);
    }
}
