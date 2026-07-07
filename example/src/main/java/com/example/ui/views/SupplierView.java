package com.example.ui.views;

import com.example.domain.catalogs.Supplier;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The suppliers catalog. */
@Component
public class SupplierView implements EntityView {

    @Override
    public Class<?> entity() {
        return Supplier.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("description", "email", "phone")
                .label("description", "Name")
                .sortBy("description", false);
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        // Contact details get their own card on the edit form, side by side on wide screens.
        f.field("description").order(0).label("Name")
            .field("email").order(1).group("Contact").width("half")
            .field("phone").order(2).group("Contact").width("half")
            .field("note").order(3).widget("textarea");
    }
}
