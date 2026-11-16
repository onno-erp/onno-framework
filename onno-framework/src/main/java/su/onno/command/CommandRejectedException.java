package su.onno.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An expected business-rule rejection from a command handler.
 *
 * <p>The web starter maps this exception to HTTP 422 with its {@link CommandFeedback} as the body
 * (MCP returns it as an error text). Unlike an unexpected exception, it is safe, purposeful feedback
 * for the user; {@code fieldErrors}/{@code formErrors} point at the inputs to fix, so a client that
 * collected them in a form keeps it open.</p>
 */
public final class CommandRejectedException extends RuntimeException {

    private final CommandFeedback feedback;

    private CommandRejectedException(CommandFeedback feedback) {
        super(feedback.message() != null ? feedback.message()
                : feedback.title() != null ? feedback.title() : "Command rejected");
        this.feedback = feedback;
    }

    public CommandFeedback feedback() {
        return feedback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CommandRejectedException message(String message) {
        return builder().message(message).build();
    }

    public static final class Builder {
        private CommandSeverity severity = CommandSeverity.ERROR;
        private String title;
        private String message;
        private final List<String> details = new ArrayList<>();
        private final Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
        private final List<String> formErrors = new ArrayList<>();

        public Builder severity(CommandSeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder detail(String detail) {
            if (detail != null && !detail.isBlank()) {
                details.add(detail);
            }
            return this;
        }

        public Builder details(List<String> details) {
            if (details != null) {
                details.forEach(this::detail);
            }
            return this;
        }

        public Builder fieldError(String field, String error) {
            if (field != null && !field.isBlank() && error != null && !error.isBlank()) {
                fieldErrors.computeIfAbsent(field, ignored -> new ArrayList<>()).add(error);
            }
            return this;
        }

        public Builder formError(String error) {
            if (error != null && !error.isBlank()) {
                formErrors.add(error);
            }
            return this;
        }

        public CommandRejectedException build() {
            return new CommandRejectedException(new CommandFeedback(severity, title,
                    message, details, fieldErrors, formErrors));
        }
    }
}
