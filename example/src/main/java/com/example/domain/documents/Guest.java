package com.example.domain.documents;

import com.example.domain.catalogs.Client;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

/**
 * One traveler line inside a {@link Booking}'s {@code guests} tabular section. A tabular-section row
 * extends {@link TabularSectionRow} (no annotation of its own) and is persisted as a child table of
 * the owning document — it has no independent identity or REST endpoint. Each row points at a
 * {@link com.example.domain.catalogs.Client} and flags whether they are the main guest or a child.
 * Distinct from {@link com.example.domain.catalogs.BookingStaff}, which models the assigned <em>team</em>.
 */
@Getter
@Setter
public class Guest extends TabularSectionRow {

    @Attribute
    private Ref<Client> client;

    @Attribute(displayName = "Main guest")
    private boolean mainGuest;

    @Attribute(displayName = "Is child")
    private boolean isChild;
}
