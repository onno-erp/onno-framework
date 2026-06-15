package com.example.domain.catalogs;

import com.example.domain.documents.Booking;
import com.onec.annotations.AccessControl;
import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;
import com.onec.types.Ref;

import lombok.Getter;
import lombok.Setter;

/**
 * The Booking↔Employee join catalog — the single source of truth for "which staff are assigned to
 * which booking" (housekeeper, concierge, …). One ref is a <em>document</em> ({@link Booking}) and
 * the other a catalog ({@link Employee}), so this is the document-side analogue of
 * {@link ClinicDoctor}: the {@code BookingView} (a document) surfaces a "Staff" related-list panel
 * and {@code EmployeeView} (a catalog) surfaces the mirror "Assignments" panel, both reading these
 * same join rows from opposite directions (see #110). Distinct from the booking's embedded
 * {@code guests} tabular section — that models the travellers; this models the assigned team.
 *
 * <p>It needs no view of its own — the related-list panels read and write it through the generic
 * catalog API, governed by the write roles declared here.</p>
 */
@Catalog(name = "BookingStaff", codeLength = 9, codePrefix = "BS-", context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "CLEANER"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class BookingStaff extends CatalogObject {

    @Attribute(displayName = "Booking", required = true)
    private Ref<Booking> booking;

    @Attribute(displayName = "Employee", required = true)
    private Ref<Employee> employee;

    @Attribute(displayName = "Role", length = 60)
    private String role;
}
