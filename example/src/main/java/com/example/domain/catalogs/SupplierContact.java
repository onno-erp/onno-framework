package com.example.domain.catalogs;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

/** A contact person owned by one supplier. */
@Getter
@Setter
public class SupplierContact extends TabularSectionRow {
    @Attribute(displayName = "Name", length = 200)
    @NotBlank
    private String name;

    @Attribute(displayName = "Email", length = 200)
    @Email
    private String email;

    @Attribute(displayName = "Phone", length = 50)
    private String phone;
}
