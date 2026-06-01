package com.example.ui.layouts;

import com.example.domain.catalogs.Employee;
import com.example.domain.documents.Booking;
import com.onec.ui.Layout;
import com.onec.ui.LayoutSpec;

import org.springframework.stereotype.Component;

/**
 * Persona shell for cleaning staff: a focused, task-oriented app instead of the
 * full back-office layout. Curation only — RBAC still gates the data. Resolved for
 * users with the CLEANER role (see {@link MainLayout} for the default shell).
 */
@Component
public class CleaningLayout implements Layout {

    @Override
    public String profile() {
        return "cleaning";
    }

    @Override
    public void configure(LayoutSpec layout) {
        layout.roles("CLEANER").title("Cleaning").theme("teal").priority(10);

        layout.section("Today")
                .order(0).icon("sparkles")
                .document(Booking.class);
        layout.section("Team")
                .order(1).icon("users")
                .catalog(Employee.class);
    }
}
