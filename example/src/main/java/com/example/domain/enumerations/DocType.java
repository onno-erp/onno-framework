package com.example.domain.enumerations;

import su.onno.annotations.Enumeration;

/**
 * Kind of government ID held on a {@link com.example.domain.catalogs.Client} — a code-controlled
 * {@code @Enumeration} (see {@link BookingStatus}). Reported, with the ID number, to Spain's
 * SES.HOSPEDAJES lodging registry.
 */
@Enumeration(name = "Document Types")
public enum DocType {
    PASSPORT,
    NATIONAL_ID,
    DRIVING_LICENSE,
    OTHER
}
