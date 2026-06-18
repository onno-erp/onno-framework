package com.example.ui.layouts;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Employee;
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
 * The tablet shell: a bottom tab bar like mobile, but with more room so it carries
 * an extra section. Targets {@link Viewport#TABLET}; the host pins the bar to the
 * bottom-right on this width class.
 */
@Component
public class TabletLayout implements Layout {

    @Override
    public Viewport viewport() {
        return Viewport.TABLET;
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

        layout.section("People")
                .order(2)
                .icon("users")
                .catalog(Employee.class);
    }
}
