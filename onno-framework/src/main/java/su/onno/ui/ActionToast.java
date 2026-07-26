package su.onno.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fluent description of structured action feedback shown in the application's toast stack. */
public final class ActionToast {

    private final ActionSeverity severity;
    private final String title;
    private String message;
    private final List<String> details = new ArrayList<>();
    private String dismissLabel;

    private ActionToast(ActionSeverity severity, String title) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.title = title;
    }

    public static ActionToast info(String title) {
        return new ActionToast(ActionSeverity.INFO, title);
    }

    public static ActionToast success(String title) {
        return new ActionToast(ActionSeverity.SUCCESS, title);
    }

    public static ActionToast warning(String title) {
        return new ActionToast(ActionSeverity.WARNING, title);
    }

    public static ActionToast error(String title) {
        return new ActionToast(ActionSeverity.ERROR, title);
    }

    public ActionToast message(String message) {
        this.message = message;
        return this;
    }

    public ActionToast detail(String detail) {
        if (detail != null && !detail.isBlank()) {
            details.add(detail);
        }
        return this;
    }

    public ActionToast details(List<String> details) {
        if (details != null) {
            details.forEach(this::detail);
        }
        return this;
    }

    public ActionToast dismissLabel(String dismissLabel) {
        this.dismissLabel = dismissLabel;
        return this;
    }

    ActionFeedback feedback() {
        return new ActionFeedback(severity, ActionPresentation.TOAST, title, message, details,
                null, null, dismissLabel, false);
    }
}
