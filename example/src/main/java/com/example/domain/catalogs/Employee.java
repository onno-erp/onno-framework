package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A staff member — a {@code @Catalog} of the people who run the business. Two roles in the app:
 * a booking's {@code assignedTo} points here, and the {@link com.example.domain.catalogs.BookingStaff}
 * join catalog assigns several employees to a booking. It's also the <em>identity</em> target — the
 * main layout links login accounts to Employee records by {@code email}
 * ({@code layout.identity(Employee.class, "email")}), so a signed-in user maps to their staff record.
 * {@code avatarUrl}/{@code contractUrl} demonstrate the image and file widgets.
 */
@Catalog(name = "Employees", codeLength = 6, codePrefix = "E-", context = "Rentals")
// Cleaners see the team roster (read-only); only rentals managers edit it.
@AccessControl(readRoles = {"RENTALS", "CLEANER"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Employee extends CatalogObject {

    @Attribute(displayName = "Full name", length = 200, required = true)
    private String fullName;

    @Attribute(displayName = "Avatar", length = 500)
    private String avatarUrl;

    // Streamed to POST /api/media via .widget("file"); only the reference URL is stored here.
    @Attribute(displayName = "Contract", length = 500)
    private String contractUrl;

    @Attribute(displayName = "Role", length = 50)
    private String role;

    @Attribute(displayName = "Department", length = 50)
    private String department;

    @Attribute(displayName = "Hourly rate", precision = 8, scale = 2)
    private BigDecimal hourlyRate;

    @Attribute(displayName = "Hired on")
    private LocalDate hiredOn;

    @Attribute(displayName = "Email", length = 200)
    private String email;

    @Attribute(displayName = "Mobile", length = 50)
    private String mobile;

    @Attribute(displayName = "Active")
    private boolean active;
}
