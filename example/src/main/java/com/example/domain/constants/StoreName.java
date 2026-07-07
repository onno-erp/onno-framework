package com.example.domain.constants;

import su.onno.annotations.Attribute;
import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

/**
 * A single global setting — the trading name of the shop, editable on the built-in Settings page
 * (enabled in {@code application.yaml}). A {@code @Constant} is a plain class with one value field;
 * the framework stores the single row and serves the {@code @Constant} editor. This is the whole
 * demonstration of the constants/Settings surface — one obvious knob.
 */
@Constant(name = "Store Name")
@Getter
@Setter
public class StoreName {

    @Attribute(displayName = "Store name", length = 200)
    private String value = "Onno Books";
}
