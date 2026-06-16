package com.example.ui.pages;

import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.example.domain.registers.RevenueRegister;
import com.onec.ui.Page;
import com.onec.ui.PageBuilder;
import com.onec.ui.Viewport;

import org.springframework.stereotype.Component;

/**
 * The phone home screen: the dashboard for {@link Viewport#MOBILE}. Wins over
 * {@link DashboardPage} on mobile and carries the same graphs and "cool stuff" —
 * KPI tiles with momentum, the stacked time-series, the categorical charts, the
 * calendar, and the map — but reordered for a single, scrollable phone column
 * (every widget renders full-width on mobile, so the desktop's side-by-side
 * fractions are dropped and the glance-first figures lead).
 */
@Component
public class MobileDashboardPage implements Page {

    @Override
    public String route() {
        return "/";
    }

    @Override
    public Viewport viewport() {
        return Viewport.MOBILE;
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Today");

        // ---- Glance: the headline figures, with momentum -------------------------------------
        b.widget("Revenue").type("stat").width("full").order(0).document(Bill.class)
                .config("metric", "sum").config("metricField", "gross")
                .config("groupByDate", "month").config("currency", "EUR")
                .hint("Sum of gross across all bills, with the latest month's trend.");
        b.widget("Revenue vs target").type("gauge").width("full").order(1).document(Bill.class)
                .config("metric", "sum").config("metricField", "gross")
                .config("target", "250000").config("currency", "EUR")
                .hint("Gross billed against the annual target.");
        b.widget("Bookings").type("sparkline").width("full").order(2).document(Booking.class)
                .config("groupBy", "check_in").config("groupByDate", "month");

        // ---- The graphs ----------------------------------------------------------------------
        b.widget("Revenue over time").type("chart").width("full").order(10).document(Bill.class)
                .config("kind", "area")
                .config("groupBy", "_date").config("groupByDate", "month")
                .config("seriesBy", "property_display").config("stacked", "true")
                .config("metric", "sum").config("metricField", "gross").config("currency", "EUR")
                .hint("Monthly gross, split and stacked by property.");
        b.widget("Revenue by property").type("chart").width("full").order(11).register(RevenueRegister.class)
                .config("kind", "bar").config("groupBy", "property_display")
                .config("metric", "sum").config("metricField", "gross_amount").config("currency", "EUR");
        b.widget("Bills by status").type("chart").width("full").order(12).document(Bill.class)
                .config("kind", "pie").config("groupBy", "_posted").config("metric", "count")
                .config("colors", "success,muted");
        b.widget("Bookings by channel").type("chart").width("full").order(13).document(Booking.class)
                .config("kind", "donut").config("groupBy", "channel").config("metric", "count");

        // ---- Calendar + map ------------------------------------------------------------------
        b.widget("Bookings calendar").type("calendar").width("full").order(20).document(Booking.class)
                .dateField("check_in").titleField("summary")
                .config("endDateField", "check_out")
                .config("secondaryField", "client_display,property_display")
                .config("amountField", "total_gross").config("currency", "EUR");
        b.widget("Properties map").type("map").width("full").order(21).catalog(Property.class)
                .config("geoField", "location").config("geoJsonField", "service_area")
                .titleField("displayName")
                .hint("Every property with a pinned location and any drawn service area; tap to open.");

        // ---- Recent activity -----------------------------------------------------------------
        b.widget("Recent bills").type("list").width("full").order(30).document(Bill.class).maxItems(6)
                .config("titleTemplate", "{client_display} — {property_display}")
                .config("secondaryField", "_number")
                .config("amountField", "gross").config("currency", "EUR");
    }
}
