package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A doctor. The other side of the Clinic↔Doctor many-to-many — its editor shows an inline list of
 * the clinics it works at, backed by the same {@link ClinicDoctor} join catalog as {@link Clinic}
 * (see {@code com.example.ui.views.DoctorView}). Both sides read the same join rows, so the
 * relationship stays single-sourced with no mirroring.
 */
@Catalog(name = "Doctors", codeLength = 6, codePrefix = "DR-", context = "Health")
@AccessControl(readRoles = {"RENTALS"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Doctor extends CatalogObject {

    @Attribute(displayName = "Specialty", length = 100)
    private String specialty;

    @Attribute(displayName = "Email", length = 200)
    private String email;
}
