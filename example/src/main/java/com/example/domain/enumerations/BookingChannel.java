package com.example.domain.enumerations;

import su.onno.annotations.Enumeration;

/**
 * Where a {@link com.example.domain.documents.Booking} originated. A code-controlled
 * {@code @Enumeration} (see {@link BookingStatus} for the concept); the dashboard's "Bookings by
 * channel" donut groups on it.
 */
@Enumeration(name = "Booking Channels")
public enum BookingChannel {
    DIRECT,
    AIRBNB,
    BOOKING_COM,
    VRBO,
    AGENCY,
    OTHER
}
