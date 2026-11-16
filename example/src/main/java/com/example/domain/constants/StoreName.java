package com.example.domain.constants;

import su.onno.annotations.Attribute;
import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

/**
 * A single global setting — the trading name of the shop, edited on the React Settings page
 * ({@code /settings}, {@code src/main/ui/pages/SettingsPage.tsx}) via a {@code "setting"} widget bound to
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
