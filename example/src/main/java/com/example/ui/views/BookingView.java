package com.example.ui.views;

import com.example.domain.catalogs.BookingStaff;
import com.example.domain.documents.Booking;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;
import com.onec.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * Default booking list — the full back-office view. A profile-specific view
 * (see {@link CleaningBookingView}) can replace this for a persona.
 *
 * <p>The booking detail also hosts a "Staff" related-list panel, backed by the
 * {@link BookingStaff} join catalog — the document-side parity with the catalog related-list
 * panels (#110). {@code via("booking")} scopes rows to this booking; {@code display("employee")}
 * is the staff member per row. {@code EmployeeView} reads the same join rows from the other side.</p>
 */
@Component
public class BookingView implements EntityView {

    @Override
    public Class<?> entity() {
        return Booking.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("number", "date", "property", "checkIn", "checkOut", "primaryClient", "status", "totalGross")
                .label("primaryClient", "Guest")
                .label("totalGross", "Total")
                // Declarative filters drive the list query itself (unlike toolbar inputs), and all of
                // them AND together. A check-in date range and a nights SELECT narrow the rows
                // server-side; a multi-select over status and a contains typeahead over the summary
                // round out the control types.
                .filter("checkIn").label("Check-in").dateRange();
        list.filter("nights").label("Nights").options("1", "2", "3", "4", "5", "6", "7");
        list.filter("status").label("Status")
                .multiOptions("DRAFT", "CONFIRMED", "CHECKED_IN", "CHECKED_OUT", "CANCELED");
        list.filter("summary").label("Summary").contains();
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("property").order(0)
                .field("status").order(1)
                .field("channel").order(2)
                // Display formatting (.format) — dates as DD-MM-YY, money as euros — applied in
                // both the list table and the detail surface.
                .field("date").format("dd-MM-yy")
                .field("checkIn").order(3).format("dd-MM-yy")
                .field("checkOut").order(4).format("dd-MM-yy")
                .field("adults").order(5)
                .field("children").order(6)
                .field("nights").order(7).hideInForm()
                .field("nightRate").order(8).format("currency:EUR")
                .field("cleaningFee").order(9).format("currency:EUR")
                .field("totalGross").order(10).hideInForm().format("currency:EUR")
                .field("summary").order(11).hideInForm()
                .field("primaryClient").order(12)
                .field("assignedTo").order(13)
                .field("notes").order(20);

        // Document-side related-list panel: the staff assigned to this booking, read/written
        // through the BookingStaff join catalog (the reverse panel lives on EmployeeView).
        f.relatedList("staff", BookingStaff.class)
                .via("booking")        // Ref<Booking> that scopes join rows to this booking
                .display("employee")   // Ref<Employee> shown / picked per row
                .columns("employee", "role")
                .label("Staff");
    }
}
