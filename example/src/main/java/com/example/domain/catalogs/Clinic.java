package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A clinic. One side of the Clinic↔Doctor many-to-many — its editor shows an inline roster of
 * doctors via a related-list panel backed by the {@link ClinicDoctor} join catalog (see
 * {@code com.example.ui.views.ClinicView}). The relationship itself lives only in the join rows;
 * Clinic stores no list of doctors.
 */
@Catalog(name = "Clinics", codeLength = 6, codePrefix = "CL-", context = "Health")
@AccessControl(readRoles = {"RENTALS"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Clinic extends CatalogObject {

    @Attribute(displayName = "Address", length = 200)
    private String address;

    @Attribute(displayName = "City", length = 100)
    private String city;

    @Attribute(displayName = "Phone", length = 50)
    private String phone;
}
