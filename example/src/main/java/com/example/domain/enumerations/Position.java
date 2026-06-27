package com.example.domain.enumerations;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * An {@link com.example.domain.catalogs.Employee}'s job. A small code-controlled list (no colors —
 * not every enum needs them); the {@code @EnumLabel}s give the values their display text.
 */
@Enumeration(name = "Positions", title = "Position")
public enum Position {
    @EnumLabel("Store manager") MANAGER,
    @EnumLabel("Bookseller") BOOKSELLER,
    @EnumLabel("Fulfilment") FULFILMENT
}
