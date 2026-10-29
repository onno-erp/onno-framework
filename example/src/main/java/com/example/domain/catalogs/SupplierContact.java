package com.example.domain.catalogs;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

/** A contact person owned by one supplier. */
@Getter
@Setter
public class SupplierContact extends TabularSectionRow {
    @Attribute(displayName = "Name", required = true, length = 200)
    private String name;

    @Attribute(displayName = "Email", email = true, length = 200)
    private String email;

    @Attribute(displayName = "Phone", length = 50)
    private String phone;
}
