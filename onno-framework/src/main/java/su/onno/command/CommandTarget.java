package su.onno.command;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a {@link Command} operates on. */
public enum CommandTarget {
    /** One record; the caller supplies its id. */
    RECORD,
    /** The entity's collection as a whole; there is no record id. */
    COLLECTION;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
