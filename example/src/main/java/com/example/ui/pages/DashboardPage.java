package com.example.ui.pages;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.onec.ui.Page;
import com.onec.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * The home dashboard, authored as a {@link Page} (was the {@code layout.widget(...)}
 * block in UiConfig). Composes the widget grid in code; {@code b.text(...)} /
 * {@code b.custom(...)} are available for freeform blocks beyond widgets.
 */
@Component
public class DashboardPage implements Page {

    @Override
    public String route() {
        return "/";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Dashboard");

        b.widget("Properties").type("count").width("1/4").order(0).catalog(Property.class);
        b.widget("Clients").type("count").width("1/4").order(1).catalog(Client.class);
        b.widget("Upcoming bookings").type("count").width("1/4").order(2).document(Booking.class);
        b.widget("Open bills").type("count").width("1/4").order(3).document(Bill.class);

        b.widget("Bookings calendar").type("calendar").width("full").order(4).document(Booking.class)
                .dateField("check_in").titleField("summary")
                .config("endDateField", "check_out")
                .config("secondaryField", "client_display,property_display");

        b.widget("Bookings by status").type("kanban").width("1/2").order(5).document(Booking.class)
                .config("groupBy", "_posted").maxItems(12);

        b.widget("Revenue by property").type("chart").width("1/2").order(6).document(Bill.class)
                .config("kind", "bar").config("groupBy", "property_display")
                .config("metric", "sum").config("metricField", "total");

        b.widget("Bills by status").type("chart").width("1/2").order(7).document(Bill.class)
                .config("kind", "donut").config("groupBy", "_posted").config("metric", "count");

        b.widget("Recent bills").type("list").width("1/2").order(8).document(Bill.class).maxItems(8);
    }
}
