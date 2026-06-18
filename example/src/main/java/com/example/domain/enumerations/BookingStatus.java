package com.example.domain.enumerations;

import su.onno.annotations.Enumeration;

/**
 * Lifecycle state of a {@link com.example.domain.documents.Booking}. An {@code @Enumeration} is a
 * fixed list <em>controlled by code</em> (contrast a {@code @Catalog}, which users edit at runtime):
 * put the annotation on a plain Java {@code enum} and the framework treats the constants as the
 * allowed values, rendered as a dropdown. This one drives real behavior — {@code BookingView}'s
 * state-aware row actions branch on it (Cancel ⇄ Reinstate, Confirm only on DRAFT), and
 * {@code Booking.handlePosting} skips a CANCELED booking.
 */
@Enumeration(name = "Booking Statuses")
public enum BookingStatus {
    DRAFT,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELED
}
