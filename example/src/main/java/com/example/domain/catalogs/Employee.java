package com.example.domain.catalogs;

import com.example.domain.enumerations.Position;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A member of staff. Doubles as the identity directory: a signed-in login is linked to its
 * {@code Employee} row by {@code email} (see {@link com.example.IdentityConfiguration}), so the
 * person can be greeted and shown as a comment author, and an order can be assigned to them.
 *
 * <p>Writing employees is ADMIN-only — the role grants ({@link com.example.access.AccessConfiguration})
 * give MANAGER read (so the order "Assigned to" picker works) but reserve writes for ADMIN. The
 * human label is the inherited {@code description} (the person's name).</p>
 */
@Catalog(name = "Employees", codePrefix = "E-", context = "People")
@Getter
@Setter
public class Employee extends CatalogObject {

    @Attribute(displayName = "Email", length = 200, email = true)
    private String email;

    @Attribute(displayName = "Position")
    private Position position = Position.BOOKSELLER;

    /** Staff photo (an image URL). Used as the avatar in the "Assign" row menu
     *  ({@link com.example.commands.OrderCommands}) and the Employees list. */
    @Attribute(displayName = "Photo", length = 500)
    private String avatarUrl;
}
