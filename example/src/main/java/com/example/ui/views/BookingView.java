package com.example.ui.views;

import com.example.domain.catalogs.BookingStaff;
import com.example.domain.documents.Booking;
import com.example.domain.enumerations.BookingStatus;
import com.example.repositories.BookingRepository;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionSpec;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default booking list — the full back-office view. A profile-specific view
 * (see {@link CleaningBookingView}) can replace this for a persona.
 *
 * <p>The booking detail also hosts a "Staff" related-list panel, backed by the
 * {@link BookingStaff} join catalog — the document-side parity with the catalog related-list
 * panels (#110). {@code via("booking")} scopes rows to this booking; {@code display("employee")}
 * is the staff member per row. {@code EmployeeView} reads the same join rows from the other side.</p>
 *
 * <p>It also demonstrates <b>state-aware row actions</b> (#116): one per-row control whose icon and
 * label flip with the booking's {@code status} (Cancel ⇄ Reinstate), disabled on a checked-in
 * booking; plus a "Confirm" action shown only on {@code DRAFT} rows. The icon/label/visibility are
 * functions of the row, evaluated as the list renders.</p>
 */
@Component
public class BookingView implements EntityView {

    private final BookingRepository bookings;

    public BookingView(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Override
    public Class<?> entity() {
        return Booking.class;
    }

    /** Opt this document into the per-entity comment thread (off by default). Staff leave
     *  handover notes on a booking; CleaningBookingView inherits this opt-in. */
    @Override
    public boolean comments() {
        return true;
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
        // value→label split: the query still matches the stored enum names (DRAFT, …) while the
        // dropdown shows friendly titles. A LinkedHashMap keeps the choices in lifecycle order.
        list.filter("status").label("Status").multiOptions(statusLabels());
        list.filter("summary").label("Summary").contains();
    }

    /** The booking statuses in lifecycle order, mapped from their stored enum name to a display title. */
    private static Map<String, String> statusLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(BookingStatus.DRAFT.name(), "Draft");
        labels.put(BookingStatus.CONFIRMED.name(), "Confirmed");
        labels.put(BookingStatus.CHECKED_IN.name(), "Checked in");
        labels.put(BookingStatus.CHECKED_OUT.name(), "Checked out");
        labels.put(BookingStatus.CANCELED.name(), "Canceled");
        return labels;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("property").order(0)
                .field("status").order(1)
                        .hint("Booking lifecycle. Posting to the revenue register is gated on CONFIRMED or later.")
                .field("channel").order(2)
                        .hint("Where the booking originated — direct, Airbnb, Booking.com, …")
                // Display formatting (.format) — dates as DD-MM-YY, money as euros — applied in
                // both the list table and the detail surface.
                .field("date").format("dd-MM-yy")
                .field("checkIn").order(3).format("dd-MM-yy")
                .field("checkOut").order(4).format("dd-MM-yy")
                .field("adults").order(5)
                .field("children").order(6)
                .field("nights").order(7).hideInForm()
                .field("nightRate").order(8).format("currency:EUR")
                        .hint("Agreed rate per night; the property's default rate pre-fills it.")
                .field("cleaningFee").order(9).format("currency:EUR")
                .field("totalGross").order(10).hideInForm().format("currency:EUR")
                        .hint("Auto-computed: nights × rate + cleaning fee. Read-only.")
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

    /**
     * State-aware per-row actions (#116). The same row control reads each booking's {@code status}
     * (via {@code row.enumValue("status", …)}) and adapts: a {@code ban} "Cancel" on an active
     * booking flips to a {@code rotate-ccw} "Reinstate" once canceled, and is disabled on a
     * checked-in booking (which shouldn't be canceled from the list). A second action, "Confirm",
     * is shown only on {@code DRAFT} rows. The icon/label/visibility/enabled are functions of the
     * row, evaluated server-side as the list renders; the handler mutates and refreshes.
     */
    @Override
    public void actions(ActionSpec a) {
        a.action("toggleStatus").scope(ActionScope.ROW)
                .icon(row -> row.enumValue("status", BookingStatus.class) == BookingStatus.CANCELED
                        ? "rotate-ccw" : "ban")
                .label(row -> row.enumValue("status", BookingStatus.class) == BookingStatus.CANCELED
                        ? "Reinstate" : "Cancel")
                .enabledWhen(row -> row.enumValue("status", BookingStatus.class) != BookingStatus.CHECKED_IN)
                .handler(ctx -> bookings.findById(ctx.id()).map(b -> {
                    boolean canceling = b.getStatus() != BookingStatus.CANCELED;
                    b.setStatus(canceling ? BookingStatus.CANCELED : BookingStatus.CONFIRMED);
                    bookings.save(b);
                    return ActionResult.refresh(canceling ? "Booking canceled" : "Booking reinstated");
                }).orElseGet(() -> ActionResult.message("Booking not found")));

        a.action("confirm").scope(ActionScope.ROW).icon("check").label("Confirm")
                .visibleWhen(row -> row.enumValue("status", BookingStatus.class) == BookingStatus.DRAFT)
                .handler(ctx -> bookings.findById(ctx.id()).map(b -> {
                    b.setStatus(BookingStatus.CONFIRMED);
                    bookings.save(b);
                    return ActionResult.refresh("Booking confirmed");
                }).orElseGet(() -> ActionResult.message("Booking not found")));
    }
}
