package su.onno.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An expected business-rule rejection from an action handler.
 *
 * <p>The UI starter maps this exception to HTTP 422 with structured {@link ActionFeedback}. Unlike
 * an unexpected exception, it is safe and purposeful feedback for the user and, when submitted
 * from an action form, keeps that form open by default.</p>
 */
public final class ActionRejectedException extends RuntimeException {

    private final ActionFeedback feedback;

    private ActionRejectedException(ActionFeedback feedback) {
        super(feedback.message() != null ? feedback.message()
                : feedback.title() != null ? feedback.title() : "Action rejected");
        this.feedback = feedback;
    }

    public ActionFeedback feedback() {
        return feedback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ActionRejectedException message(String message) {
        return builder().message(message).build();
    }

    public static final class Builder {
        private ActionSeverity severity = ActionSeverity.ERROR;
        private ActionPresentation presentation = ActionPresentation.DIALOG;
        private String title;
        private String message;
        private final List<String> details = new ArrayList<>();
        private final Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
        private final List<String> formErrors = new ArrayList<>();
        private String dismissLabel;
        private boolean keepFormOpen = true;

        public Builder severity(ActionSeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder presentation(ActionPresentation presentation) {
            this.presentation = presentation;
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

        public Builder dismissLabel(String dismissLabel) {
            this.dismissLabel = dismissLabel;
            return this;
        }

        public Builder keepFormOpen(boolean keepFormOpen) {
            this.keepFormOpen = keepFormOpen;
            return this;
        }

        public ActionRejectedException build() {
            return new ActionRejectedException(new ActionFeedback(severity, presentation, title,
                    message, details, fieldErrors, formErrors, dismissLabel, keepFormOpen));
        }
    }
}
