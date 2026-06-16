package com.example.ui.pages;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.example.domain.registers.RevenueRegister;
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

        b.widget("Properties").type("count").width("1/4").order(0).catalog(Property.class)
                .hint("Total rental units in the catalog.");
        b.widget("Clients").type("count").width("1/4").order(1).catalog(Client.class);
        // FR-5: a count narrowed by a safe filter predicate (system column).
        b.widget("Posted bills").type("count").width("1/4").order(2).document(Bill.class)
                .config("filter", "_posted = true")
                .hint("Bills posted to the ledger; drafts are excluded.");
        // FR-1 + FR-6: a server-aggregated KPI (SUM of gross) as a currency-formatted card.
        // Unit placement: render the symbol as a suffix ("174,831.73 €") rather than the
        // locale's default euro prefix. `unit` wins over `currency`; `unitPosition` is suffix by default.
        b.widget("Revenue").type("metric").width("1/4").order(3).document(Bill.class)
                .config("metric", "sum").config("metricField", "gross")
                .config("unit", "€").config("unitPosition", "suffix")
                // A widget hint surfaces as a hoverable "?" next to the card title.
                .hint("Sum of gross on all bills, including unposted drafts.");

        b.widget("Bookings calendar").type("calendar").width("full").order(4).document(Booking.class)
                .dateField("check_in").titleField("summary")
                .config("endDateField", "check_out")
                .config("secondaryField", "client_display,property_display")
                // FR-6: render the booking amount as EUR instead of a hardcoded $.
                .config("amountField", "total_gross").config("currency", "EUR");

        // Hidden for the moment — restore to bring the Bookings-by-status kanban back.
        // b.widget("Bookings by status").type("kanban").width("1/2").order(5).document(Booking.class)
        //         .config("groupBy", "_posted").maxItems(12);

        // FR-4: chart sourced from the Revenue accumulation register's server-side turnover.
        b.widget("Revenue by property").type("chart").width("1/2").order(6).register(RevenueRegister.class)
                .config("kind", "bar").config("groupBy", "property_display")
                .config("metric", "sum").config("metricField", "gross_amount").config("currency", "EUR");

        // FR-3: a true pie (alias of donut with innerRadius 0).
        b.widget("Bills by status").type("chart").width("1/2").order(7).document(Bill.class)
                .config("kind", "pie").config("groupBy", "_posted").config("metric", "count");

        // FR-2 + FR-6 + FR-7: configurable list rows — templated title, EUR amount field, _number on line 2.
        b.widget("Recent bills").type("list").width("1/2").order(8).document(Bill.class).maxItems(8)
                .config("titleTemplate", "{client_display} — {property_display}")
                .config("secondaryField", "_number")
                .config("amountField", "gross").config("currency", "EUR");
    }
}
