package com.example.ui.views;

import com.example.domain.catalogs.Customer;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * The customers catalog — with a <b>map view</b>. {@code list.map()} adds a Table⇄Map toggle to the
 * list: each customer with coordinates is plotted as a marker (its name in the popup). The list still
 * opens as a table by default; {@code .defaultView()} would open straight on the map instead.
 */
@Component
public class CustomerView implements EntityView {

    @Override
    public Class<?> entity() {
        return Customer.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("description", "city", "email", "phone")
                .label("description", "Name")
                .sortBy("description", false)
                .groupable("city");
        // A single-select city facet (the seeder's spread) and an email typeahead.
        list.filter("city").label("City").options(
                "New York", "Los Angeles", "Chicago", "Toronto", "London", "Paris", "Berlin",
                "Madrid", "Rome", "Amsterdam", "Tokyo", "Singapore", "Dubai", "Mumbai",
                "Sydney", "São Paulo", "Mexico City", "Cape Town");
        list.filter("email").label("Email").contains();
        // Plot each customer from its latitude/longitude; the marker popup shows the name.
        list.map().lat("latitude").lng("longitude").label("description");
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("description").order(0).label("Name")
            .field("email").order(1)
            .field("phone").order(2)
            .field("city").order(3)
            .field("latitude").order(4)
            .field("longitude").order(5);
    }
}
