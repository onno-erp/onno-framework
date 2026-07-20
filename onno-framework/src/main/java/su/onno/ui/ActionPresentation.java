package su.onno.ui;

import com.fasterxml.jackson.annotation.JsonValue;

/** Where the client presents action feedback. */
public enum ActionPresentation {
    TOAST,
    DIALOG,
    INLINE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
