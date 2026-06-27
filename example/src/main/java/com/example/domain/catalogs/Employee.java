package com.example.domain.catalogs;

import com.example.domain.enumerations.Position;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A member of staff. Doubles as the identity directory: a signed-in login is linked to its
 * {@code Employee} row by {@code email} (see {@link com.example.ui.layouts.MainLayout}), so the
 * person can be greeted and shown as a comment author, and an order can be assigned to them.
 *
 * <p>Writing employees is ADMIN-only — {@code @AccessControl} grants MANAGER read (so the order
 * "Assigned to" picker works) but reserves writes for ADMIN. The human label is the inherited
 * {@code description} (the person's name).</p>
 */
@Catalog(name = "Employees", title = "Employee", codePrefix = "E-", context = "People")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"ADMIN"})
@Getter
@Setter
public class Employee extends CatalogObject {

    @Attribute(displayName = "Email", length = 200, email = true)
    private String email;

    @Attribute(displayName = "Position")
    private Position position = Position.BOOKSELLER;
}
