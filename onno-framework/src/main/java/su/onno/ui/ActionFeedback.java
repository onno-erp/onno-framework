package su.onno.ui;

import java.util.List;
import java.util.Map;

/**
 * Structured, semantic feedback from a server-side action.
 *
 * @param severity action outcome tone
 * @param presentation preferred client surface
 * @param title optional heading
 * @param message primary explanation
 * @param details optional readable detail/bullet lines
 * @param fieldErrors action-form errors keyed by input name
 * @param formErrors cross-field/action-form errors
 * @param dismissLabel optional acknowledgement button label
 * @param keepFormOpen whether a submitting action form remains open (normally true for rejection)
 */
public record ActionFeedback(
        ActionSeverity severity,
        ActionPresentation presentation,
        String title,
        String message,
        List<String> details,
        Map<String, List<String>> fieldErrors,
        List<String> formErrors,
        String dismissLabel,
        boolean keepFormOpen) {

    public ActionFeedback {
        severity = severity == null ? ActionSeverity.INFO : severity;
        presentation = presentation == null ? ActionPresentation.TOAST : presentation;
        details = details == null ? List.of() : List.copyOf(details);
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
        formErrors = formErrors == null ? List.of() : List.copyOf(formErrors);
    }
}
