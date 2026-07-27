package com.example.domain.catalogs;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A shelf/genre a {@link Book} belongs to (Fiction, Children's, Science, …). The simplest possible
 * {@code @Catalog}: the inherited {@code code} ({@code CAT-…}) and {@code description} hold its
 * identity, while {@code color} demonstrates a user-editable visual attribute. Runtime-editable
 * master data: staff add a category without a code change.
 */
@Catalog(name = "Categories", title = "Category", codePrefix = "CAT-", context = "Catalog")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
@Getter
@Setter
public class BookCategory extends CatalogObject {

    @Attribute(displayName = "Color", length = 7, pattern = "#[0-9A-Fa-f]{6}")
    private String color;
}
