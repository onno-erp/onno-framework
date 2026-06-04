package com.onec.ui;

/** Where a custom {@link ActionSpec action} button appears. */
public enum ActionScope {
    /** A toolbar button on the list surface (operates on the whole list / no specific row). */
    TOOLBAR,
    /** A per-row button on the list surface (operates on that row's record). */
    ROW,
    /** A button in a record's detail header. */
    DETAIL
}
