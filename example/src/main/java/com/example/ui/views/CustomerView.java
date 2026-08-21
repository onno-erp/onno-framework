package com.example.ui.views;

import com.example.domain.catalogs.Customer;
import su.onno.ui.DetailSpec;
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
public class CustomerView implements EntityView<Customer> {

    @Override
    public Class<Customer> entity() {
        return Customer.class;
    }

    @Override
    public void list(ListSpec<Customer> list) {
        list.columns(Customer::getDescription, Customer::getStatus, Customer::getCity,
                        Customer::getEmail, Customer::getPhone)
                .label(Customer::getDescription, "Name")
                .label(Customer::getStatus, "Status")
                .sortBy(Customer::getDescription, false)
                // Opens grouped by city (the "Group by ▾" picker can still switch back to None).
                .groupable(Customer::getCity).defaultGroupBy(Customer::getCity);
        // A multi-select city facet (the seeder's spread) and an email typeahead.
        list.filter(Customer::getCity).label("City").options(
                "New York", "Los Angeles", "Chicago", "Toronto", "London", "Paris", "Berlin",
                "Madrid", "Rome", "Amsterdam", "Tokyo", "Singapore", "Dubai", "Mumbai",
                "Sydney", "São Paulo", "Mexico City", "Cape Town").multiple();
        list.filter(Customer::getEmail).label("Email").contains();
        // Plot each customer from its latitude/longitude; the marker popup shows the name.
        list.map().lat(Customer::getLatitude).lng(Customer::getLongitude)
                .label(Customer::getDescription);
    }

    @Override
    public void fields(EntityConfigBuilder<Customer> f) {
        f.field(Customer::getDescription).order(0).label("Name")
            .field(Customer::getStatus).order(1).label("Client status")
            .field(Customer::getEmail).order(2)
            .field(Customer::getPhone).order(3)
            .field(Customer::getCity).order(4)
            .field(Customer::getLatitude).order(5)
            .field(Customer::getLongitude).order(6);
    }

    @Override
    public void detail(DetailSpec<Customer> detail) {
        detail.widget("Client conversations").type("customerConversations").width("full")
                .hint("Recent client touchpoints and a mock AI relationship summary.");
    }
}
