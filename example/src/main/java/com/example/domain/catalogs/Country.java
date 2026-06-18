package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * Reference data — the country list a {@link Client}'s {@code nationality} and {@code country} point
 * at. A plain lookup {@code @Catalog} with no documents or posting behind it; it sits under the
 * "Reference" nav section and is also the catalog managed inline on the Settings page. Note there is
 * no {@code codePrefix} — codes are the bare 3-character value.
 */
@Catalog(name = "Countries", codeLength = 3, context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Country extends CatalogObject {

    @Attribute(displayName = "ISO 2", length = 2)
    private String iso2;

    @Attribute(displayName = "Name (English)", length = 100, required = true)
    private String name;

    @Attribute(displayName = "Nationality", length = 100)
    private String nationality;
}
