package su.onno.ui;

/**
 * One message returned by a {@link FormValidator}. A blank field targets the whole form; a field
 * name (including a path such as {@code participants.employee}) places the message beside that
 * control as well as exposing it to assistive technology.
 */
public record FormFeedback(FormFeedbackSeverity severity, String field, String message) {
    public FormFeedback {
        severity = severity == null ? FormFeedbackSeverity.ERROR : severity;
        field = field == null ? "" : field;
        message = message == null ? "" : message;
    }

    public static FormFeedback error(String field, String message) {
        return new FormFeedback(FormFeedbackSeverity.ERROR, field, message);
    }

    public static FormFeedback warning(String field, String message) {
        return new FormFeedback(FormFeedbackSeverity.WARNING, field, message);
    }

    public static FormFeedback info(String field, String message) {
        return new FormFeedback(FormFeedbackSeverity.INFO, field, message);
    }

    public static FormFeedback formError(String message) {
        return error("", message);
    }
}
