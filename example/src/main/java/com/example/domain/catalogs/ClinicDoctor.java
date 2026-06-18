package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

/**
 * The Clinic↔Doctor join catalog — the single source of truth for "which doctor works at which
 * clinic". Two refs model the many-to-many; an extra {@code role} attribute rides along on each
 * link. Neither {@link Clinic} nor {@link Doctor} mirrors the relationship: both editors show
 * "their" rows through related-list panels over these same join records (see the views).
 *
 * <p>It needs no view of its own — the related-list panels read and write it through the generic
 * catalog API, governed by the write roles declared here.</p>
 */
@Catalog(name = "ClinicDoctor", codeLength = 9, codePrefix = "CD-", context = "Health")
@AccessControl(readRoles = {"RENTALS"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class ClinicDoctor extends CatalogObject {

    @Attribute(displayName = "Clinic", required = true)
    private Ref<Clinic> clinic;

    @Attribute(displayName = "Doctor", required = true)
    private Ref<Doctor> doctor;

    @Attribute(displayName = "Role", length = 60)
    private String role;
}
