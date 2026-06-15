package com.example.ui.views;

import com.example.domain.catalogs.BookingStaff;
import com.example.domain.catalogs.Employee;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the Employee catalog as a visible surface and its field order. Shown in
 * the default back-office nav and reused by the cleaning persona's "Team" section.
 *
 * <p>Hosts the mirror of {@code BookingView}'s "Staff" panel: an "Assignments" related-list panel
 * over the same {@link BookingStaff} join catalog, scoped the other way — {@code via("employee")}
 * ties rows to this employee, {@code display("booking")} is the booking (a document) per row. One
 * join catalog, two inline rosters, no duplicated relationship (see #110).</p>
 */
@Component
public class EmployeeView implements EntityView {

    @Override
    public Class<?> entity() {
        return Employee.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("avatarUrl").order(-1).widget("avatar")
                .field("fullName").order(0)
                .field("role").order(1)
                .field("department").order(2)
                .field("hourlyRate").order(3)
                .field("hiredOn").order(4)
                .field("email").order(5)
                .field("mobile").order(6)
                .field("active").order(7)
                .field("contractUrl").order(8).widget("file");

        f.relatedList("assignments", BookingStaff.class)
                .via("employee")       // Ref<Employee> that scopes join rows to this employee
                .display("booking")    // Ref<Booking> (a document) shown / picked per row
                .columns("booking", "role")
                .label("Assignments");
    }
}
