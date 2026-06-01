package com.example.ui.pages;

import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.onec.ui.Page;
import com.onec.ui.PageBuilder;
import com.onec.ui.Viewport;

import org.springframework.stereotype.Component;

/**
 * The phone home screen: a trimmed dashboard for {@link Viewport#MOBILE}. Wins
 * over {@link DashboardPage} on mobile — a couple of headline counts and a recent
 * list instead of the full widget grid, calendar, and charts.
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

        b.widget("Upcoming bookings").type("count").width("1/2").order(0).document(Booking.class);
        b.widget("Open bills").type("count").width("1/2").order(1).document(Bill.class);
        b.widget("Properties").type("count").width("1/2").order(2).catalog(Property.class);

        b.widget("Recent bills").type("list").width("full").order(3).document(Bill.class).maxItems(6);
    }
}
