package su.onno.ui;

import com.fasterxml.jackson.annotation.JsonValue;

/** Semantic tone of feedback returned by a server-side action. */
public enum ActionSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
