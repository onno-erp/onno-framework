package com.example.ui.pages;

import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.example.domain.registers.RevenueRegister;
import com.onec.ui.Page;
import com.onec.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * The home dashboard, authored as a {@link Page}. Showcases the data-widget DSL end to end: KPI
 * tiles with trend ({@code stat}/{@code sparkline}/{@code gauge}), a multi-series time-series chart
 * ({@code seriesBy} splits one chart into colored, stacked series), and the categorical charts.
 * Colors come from the theme palette by default; {@code config("colors", ...)} overrides per widget.
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

        // ---- KPI row: figures with momentum, not just static numbers -------------------------
        // A stat tile: the headline total + period-over-period trend + a sparkline of the series.
        b.widget("Revenue").type("stat").width("1/4").order(0).document(Bill.class)
                .config("metric", "sum").config("metricField", "gross")
                .config("groupByDate", "month").config("currency", "EUR")
                .hint("Sum of gross across all bills, with the latest month's trend.");
        // A bare sparkline: count of bookings per month by check-in date.
        b.widget("Bookings").type("sparkline").width("1/4").order(1).document(Booking.class)
                .config("groupBy", "check_in").config("groupByDate", "month");
        // A radial gauge: revenue against a target (the ring fills toward it).
        b.widget("Revenue vs target").type("gauge").width("1/4").order(2).document(Bill.class)
                .config("metric", "sum").config("metricField", "gross")
                .config("target", "250000").config("currency", "EUR")
                .hint("Gross billed against the annual target.");
        // A plain count, narrowed by a safe filter predicate (system column).
        b.widget("Posted bills").type("count").width("1/4").order(3).document(Bill.class)
                .config("filter", "_posted = true")
                .hint("Bills posted to the ledger; drafts are excluded.");

        // ---- Time series: one chart, a colored series per property, stacked -------------------
        b.widget("Revenue over time").type("chart").width("full").order(10).document(Bill.class)
                .config("kind", "area")
                .config("groupBy", "_date").config("groupByDate", "month")
                .config("seriesBy", "property_display").config("stacked", "true")
                .config("metric", "sum").config("metricField", "gross").config("currency", "EUR")
                .hint("Monthly gross, split and stacked by property.");

        // ---- Categorical charts ---------------------------------------------------------------
        // Bar sourced from the Revenue register's server-side turnover (sum of a resource).
        b.widget("Revenue by property").type("chart").width("1/2").order(20).register(RevenueRegister.class)
                .config("kind", "bar").config("groupBy", "property_display")
                .config("metric", "sum").config("metricField", "gross_amount").config("currency", "EUR");
        // A pie with an explicit two-color override: posted (green) vs draft (muted).
        b.widget("Bills by status").type("chart").width("1/2").order(21).document(Bill.class)
                .config("kind", "pie").config("groupBy", "_posted").config("metric", "count")
                .config("colors", "success,muted");

        // ---- Donut + recent list --------------------------------------------------------------
        b.widget("Bookings by channel").type("chart").width("1/2").order(30).document(Booking.class)
                .config("kind", "donut").config("groupBy", "channel").config("metric", "count");
        // Configurable list rows — templated title, EUR amount field, _number on line 2.
        b.widget("Recent bills").type("list").width("1/2").order(31).document(Bill.class).maxItems(8)
                .config("titleTemplate", "{client_display} — {property_display}")
                .config("secondaryField", "_number")
                .config("amountField", "gross").config("currency", "EUR");

        // ---- Calendar -------------------------------------------------------------------------
        b.widget("Bookings calendar").type("calendar").width("full").order(40).document(Booking.class)
                .dateField("check_in").titleField("summary")
                .config("endDateField", "check_out")
                .config("secondaryField", "client_display,property_display")
                .config("amountField", "total_gross").config("currency", "EUR");

        // ---- Map: properties as markers + drawn service areas (point + GeoJSON sources) --------
        b.widget("Properties map").type("map").width("full").order(50).catalog(Property.class)
                .config("geoField", "location").config("geoJsonField", "service_area")
                .titleField("displayName")
                .hint("Every property with a pinned location and any drawn service area; click to open.");
    }
}
