package su.onno.command;

import com.fasterxml.jackson.annotation.JsonValue;

/** Semantic tone of feedback returned by a command. */
public enum CommandSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
