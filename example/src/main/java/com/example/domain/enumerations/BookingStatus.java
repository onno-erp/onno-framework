package com.example.domain.enumerations;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * Lifecycle state of a {@link com.example.domain.documents.Booking}. An {@code @Enumeration} is a
 * fixed list <em>controlled by code</em> (contrast a {@code @Catalog}, which users edit at runtime):
 * put the annotation on a plain Java {@code enum} and the framework treats the constants as the
 * allowed values, rendered as a dropdown. This one drives real behavior — {@code BookingView}'s
 * state-aware row actions branch on it (Cancel ⇄ Reinstate, Confirm only on DRAFT), and
 * {@code Booking.handlePosting} skips a CANCELED booking.
 *
 * <p>The {@code title} gives the type a display name and each {@code @EnumLabel} gives a value a
 * human-facing label, so list cells and the dropdown read "Checked in" instead of the raw
 * {@code CHECKED_IN} — without renaming the constants (their names key the stored UUIDs and the
 * row-action logic). Swap the labels for another language to localize the field.
 */
@Enumeration(name = "Booking Statuses", title = "Booking status")
public enum BookingStatus {
    @EnumLabel("Draft") DRAFT,
    @EnumLabel("Confirmed") CONFIRMED,
    @EnumLabel("Checked in") CHECKED_IN,
    @EnumLabel("Checked out") CHECKED_OUT,
    @EnumLabel("Canceled") CANCELED
}
