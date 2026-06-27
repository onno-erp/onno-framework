package com.example.ui.views;

import com.example.domain.catalogs.Customer;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/** The customers catalog. */
@Component
public class CustomerView implements EntityView {

    @Override
    public Class<?> entity() {
        return Customer.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("description", "email", "phone")
                .label("description", "Name")
                .sortBy("description", false);
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("description").order(0).label("Name")
            .field("email").order(1)
            .field("phone").order(2)
            .field("address").order(3).widget("textarea");
    }
}
