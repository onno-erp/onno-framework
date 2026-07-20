package su.onno.ui;

import com.fasterxml.jackson.annotation.JsonValue;

/** Width preset for an action form or feedback dialog. */
public enum DialogSize {
    SM,
    MD,
    LG;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
