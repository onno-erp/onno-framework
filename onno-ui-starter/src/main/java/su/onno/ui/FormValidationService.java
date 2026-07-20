package su.onno.ui;

import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves application-provided {@link FormValidator} Spring beans by their configured type. */
public final class FormValidationService {
    private final Map<Class<?>, FormValidator> validators;

    public FormValidationService(List<FormValidator> validators) {
        Map<Class<?>, FormValidator> byType = new LinkedHashMap<>();
        for (FormValidator validator : validators) {
            byType.put(ClassUtils.getUserClass(validator), validator);
        }
        this.validators = Map.copyOf(byType);
    }

    public List<FormFeedback> validate(FormValidation definition, FormValidationContext context) {
        FormValidator validator = validators.get(definition.validator());
        if (validator == null) {
            throw new IllegalStateException("No FormValidator bean registered for "
                    + definition.validator().getName());
        }
        List<FormFeedback> feedback = validator.validate(context);
        return feedback == null ? List.of() : feedback.stream()
                .filter(item -> item != null && !item.message().isBlank())
                .toList();
    }
}
