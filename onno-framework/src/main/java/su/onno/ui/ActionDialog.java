package su.onno.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fluent description of successful action feedback that requires acknowledgement in a dialog. */
public final class ActionDialog {

    private final ActionSeverity severity;
    private final String title;
    private String message;
    private final List<String> details = new ArrayList<>();
    private String dismissLabel;

    private ActionDialog(ActionSeverity severity, String title) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.title = title;
    }

    public static ActionDialog info(String title) {
        return new ActionDialog(ActionSeverity.INFO, title);
    }

    public static ActionDialog success(String title) {
        return new ActionDialog(ActionSeverity.SUCCESS, title);
    }

    public static ActionDialog warning(String title) {
        return new ActionDialog(ActionSeverity.WARNING, title);
    }

    public ActionDialog message(String message) {
        this.message = message;
        return this;
    }

    public ActionDialog detail(String detail) {
        if (detail != null && !detail.isBlank()) {
            details.add(detail);
        }
        return this;
    }

    public ActionDialog details(List<String> details) {
        if (details != null) {
            details.forEach(this::detail);
        }
        return this;
    }

    public ActionDialog dismissLabel(String dismissLabel) {
        this.dismissLabel = dismissLabel;
        return this;
    }

    ActionFeedback feedback() {
        return new ActionFeedback(severity, ActionPresentation.DIALOG, title, message, details,
                null, null, dismissLabel, false);
    }
}
