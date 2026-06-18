package com.example.ui.layouts;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.example.domain.documents.Payment;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;
import su.onno.ui.Viewport;

import org.springframework.stereotype.Component;

/**
 * The mobile shell: a curated subset of the back-office nav for a phone-sized
 * bottom tab bar. Targets {@link Viewport#MOBILE}, so it fully replaces
 * {@link MainLayout} on phones while tablet/desktop keep the full sidebar. Only
 * the two everyday sections are surfaced here — the rest stay off the bottom bar.
 */
@Component
public class MobileLayout implements Layout {

    @Override
    public Viewport viewport() {
        return Viewport.MOBILE;
    }

    @Override
    public void configure(LayoutSpec layout) {
        layout.shell().nav(NavStyle.BOTTOM_BAR);

        layout.section("Rentals")
                .order(0)
                .icon("house")
                .catalog(Property.class)
                .catalog(Client.class)
                .document(Booking.class);

        layout.section("Finance")
                .order(1)
                .icon("euro")
                .document(Bill.class)
                .document(Payment.class);
    }
}
