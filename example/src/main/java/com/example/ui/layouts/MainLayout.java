package com.example.ui.layouts;

import com.example.domain.catalogs.BankAccount;
import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Clinic;
import com.example.domain.catalogs.Country;
import com.example.domain.catalogs.Doctor;
import com.example.domain.catalogs.Employee;
import com.example.domain.catalogs.Property;
import com.example.domain.documents.Bill;
import com.example.domain.documents.Booking;
import com.example.domain.documents.Payment;
import com.example.domain.registers.BankBalanceRegister;
import com.example.domain.registers.OccupancyRegister;
import com.example.domain.registers.ReceivablesRegister;
import com.example.domain.registers.RevenueRegister;
import com.onec.ui.Layout;
import com.onec.ui.LayoutSpec;
import com.onec.ui.NavStyle;

import org.springframework.stereotype.Component;

/**
 * The default back-office shell: navigation sections, per-field hints, the nav
 * presentation, and the login→Employee identity link. Replaces the old
 * {@code UiConfig} configurer — UI structure is now authored as classes
 * ({@link Layout} / {@code Page} / {@code EntityView}).
 */
@Component
public class MainLayout implements Layout {

    @Override
    public void configure(LayoutSpec layout) {
        // The universal shell: a sidebar rail. Mobile gets its own curated layout
        // (see MobileLayout); tablet/desktop fall back to this.
        layout.shell().nav(NavStyle.SIDEBAR);

        layout.section("Rentals")
                .order(0)
                .icon("house")
                // Property carries an explicit nav icon — authored icons win over the
                // name heuristic (which would otherwise pick "building").
                .catalog(Property.class, "key")
                .catalog(Client.class)
                .document(Booking.class);

        layout.section("Finance")
                .order(1)
                .icon("euro")
                .document(Bill.class)
                .document(Payment.class)
                .catalog(BankAccount.class)
                .register(ReceivablesRegister.class)
                .register(BankBalanceRegister.class);

        layout.section("People")
                .order(2)
                .icon("users")
                .catalog(Employee.class);

        // Demonstrates declarative related-list panels: Clinic and Doctor are a catalog↔catalog
        // many-to-many, edited inline on either side over the ClinicDoctor join catalog (which
        // needs no nav entry — the related-list panels read/write it directly).
        layout.section("Health")
                .order(4)
                .icon("stethoscope")
                .catalog(Clinic.class, "hospital")
                .catalog(Doctor.class, "stethoscope");

        layout.section("Reports")
                .order(3)
                .icon("chart-column")
                .register(OccupancyRegister.class)
                .register(RevenueRegister.class);

        layout.section("Reference")
                .order(9)
                .icon("book")
                .catalog(Country.class);

        // Link login accounts to Employee records, matched on email, so persona
        // UIs can greet and (later) scope to the signed-in person.
        layout.identity(Employee.class, "email");
    }
}
