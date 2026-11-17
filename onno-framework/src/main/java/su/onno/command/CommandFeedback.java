package su.onno.command;

import java.util.List;
import java.util.Map;

/**
 * What a command tells its caller about the outcome, in meaning only: how serious it is
 * ({@code severity}), what happened ({@code message}, an optional {@code title} and readable
 * {@code details}) and — for a {@link CommandRejectedException rejection} — which inputs were wrong
 * ({@code fieldErrors} keyed by input key, {@code formErrors} for the inputs as a whole). How it is
 * shown (a toast, a dialog, inline in the input form) is the client's decision; MCP renders it as
 * text.
 */
public record CommandFeedback(
        CommandSeverity severity,
        String title,
        String message,
        List<String> details,
        Map<String, List<String>> fieldErrors,
        List<String> formErrors) {

    public CommandFeedback {
        severity = severity == null ? CommandSeverity.INFO : severity;
        details = details == null ? List.of() : List.copyOf(details);
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
        formErrors = formErrors == null ? List.of() : List.copyOf(formErrors);
    }

    /** Feedback of a successful run: no input errors. */
    public static CommandFeedback of(CommandSeverity severity, String title, String message, List<String> details) {
        return new CommandFeedback(severity, title, message, details, null, null);
    }

    /** The feedback as one line of plain text: {@code title: message (detail; detail)}. */
    public String asText() {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title);
        if (message != null && !message.isBlank()) {
            if (!sb.isEmpty()) sb.append(": ");
            sb.append(message);
        }
        if (!details.isEmpty()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append('(').append(String.join("; ", details)).append(')');
        }
        return sb.toString();
    }
}
