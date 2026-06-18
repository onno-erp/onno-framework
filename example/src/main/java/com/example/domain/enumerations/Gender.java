package com.example.domain.enumerations;

import su.onno.annotations.Enumeration;

/**
 * A {@link com.example.domain.catalogs.Client}'s gender — a code-controlled {@code @Enumeration}
 * (see {@link BookingStatus} for the concept).
 */
@Enumeration(name = "Genders")
public enum Gender {
    MALE,
    FEMALE,
    OTHER
}
