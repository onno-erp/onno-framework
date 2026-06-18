package com.example.domain.enumerations;

import su.onno.annotations.Enumeration;

/**
 * How a {@link com.example.domain.documents.Payment} was made — a code-controlled
 * {@code @Enumeration} (see {@link BookingStatus} for the concept).
 */
@Enumeration(name = "Payment Methods")
public enum PaymentMethod {
    BANK_TRANSFER,
    CASH,
    CARD,
    BTC,
    OTHER
}
