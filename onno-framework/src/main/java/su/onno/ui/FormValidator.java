package su.onno.ui;

import java.util.List;

/**
 * Application-provided advisory validation for a generated form. Implementations are ordinary
 * Spring beans, so they may inject repositories and services. Save/post validation remains
 * authoritative and should enforce hard invariants independently.
 */
@FunctionalInterface
public interface FormValidator {
    List<FormFeedback> validate(FormValidationContext context);
}
