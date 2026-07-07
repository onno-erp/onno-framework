package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Someone who buys books from the shop. Referenced by a customer {@link com.example.domain.documents.Order}.
 * The human label is the inherited {@code description} (the customer's name).
 *
 * <p>Carries a {@code city} and {@code latitude}/{@code longitude} so the customers list can offer a
 * map view (a Table⇄Map toggle — see {@link com.example.ui.views.CustomerView}). The coordinates are
 * plain decimal attributes; the framework's map widget plots one marker per row.</p>
 */
@Catalog(name = "Customers", title = "Customer", codePrefix = "CU-", context = "Sales")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class Customer extends CatalogObject {

    @Attribute(displayName = "Email", length = 200, email = true)
    private String email;

    @Attribute(displayName = "Phone", length = 50)
    private String phone;

    @Attribute(displayName = "City", length = 120)
    private String city;

    @Attribute(displayName = "Latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Attribute(displayName = "Longitude", precision = 9, scale = 6)
    private BigDecimal longitude;
}
