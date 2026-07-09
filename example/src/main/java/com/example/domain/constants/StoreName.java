package com.example.domain.constants;

import su.onno.annotations.Attribute;
import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

/**
 * A single global setting — the trading name of the shop, edited on the authored Settings page
 * ({@link com.example.ui.pages.SettingsPage}) via a {@code type("setting")} input widget bound to
 * this constant. A {@code @Constant} is a plain class with one value field; the framework stores the
 * single row (read/written through {@code SettingsController}). This is the whole demonstration of
 * the constants surface — one obvious knob.
 */
@Constant(name = "Store Name")
@Getter
@Setter
public class StoreName {

    @Attribute(displayName = "Store name", length = 200)
    private String value = "Onno Books";
}
