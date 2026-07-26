package com.example.ui.views;

import com.example.domain.catalogs.Supplier;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The suppliers catalog. */
@Component
public class SupplierView implements EntityView<Supplier> {

    @Override
    public Class<Supplier> entity() {
        return Supplier.class;
    }

    @Override
    public void list(ListSpec<Supplier> list) {
        list.columns(Supplier::getDescription, Supplier::getEmail, Supplier::getPhone)
                .label(Supplier::getDescription, "Name")
                .sortBy(Supplier::getDescription, false);
    }

    @Override
    public void fields(EntityConfigBuilder<Supplier> f) {
        // Contact details get their own card on the edit form, side by side on wide screens.
        f.field(Supplier::getDescription).order(0).label("Name")
            .field(Supplier::getEmail).order(1).group("Contact").width("half")
            .field(Supplier::getPhone).order(2).group("Contact").width("half")
            .field(Supplier::getNote).order(3).widget("textarea");
    }
}
