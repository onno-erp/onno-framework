package su.onno.validation;

/**
 * One message returned by a {@link Check}. A blank field targets the whole record; a field name
 * (including a path such as {@code participants.employee}) places the message beside that field.
 */
public record Feedback(FeedbackSeverity severity, String field, String message) {
    public Feedback {
        severity = severity == null ? FeedbackSeverity.ERROR : severity;
        field = field == null ? "" : field;
        message = message == null ? "" : message;
    }

    public static Feedback error(String field, String message) {
        return new Feedback(FeedbackSeverity.ERROR, field, message);
    }

    public static Feedback warning(String field, String message) {
        return new Feedback(FeedbackSeverity.WARNING, field, message);
    }

    public static Feedback info(String field, String message) {
        return new Feedback(FeedbackSeverity.INFO, field, message);
    }

    /** An error about the record as a whole. */
    public static Feedback formError(String message) {
        return error("", message);
    }
}
