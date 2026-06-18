package su.onno.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates validation failures across all sources (declarative attribute constraints, custom
 * {@link su.onno.rules.BusinessRule}s) so the user sees every problem at once rather than one at a
 * time. {@link #throwIfAny()} raises a {@link ValidationException} when anything was collected.
 */
public final class ValidationErrors {

    private final Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
    private final List<String> formErrors = new ArrayList<>();

    /** Record a message on a field; a null/blank field falls back to a form-level message. */
    public void field(String field, String message) {
        if (field == null || field.isBlank()) {
            form(message);
            return;
        }
        fieldErrors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    /** Record a cross-field / form-level message. */
    public void form(String message) {
        formErrors.add(message);
    }

    public boolean isEmpty() {
        return fieldErrors.isEmpty() && formErrors.isEmpty();
    }

    /** Throw a {@link ValidationException} carrying everything collected, if non-empty. */
    public void throwIfAny() {
        if (isEmpty()) {
            return;
        }
        // A single problem reads better as itself (in logs / a posting toast); several get a
        // generic summary, with the per-field detail carried in fieldErrors for the form.
        List<String> all = new ArrayList<>(formErrors);
        fieldErrors.values().forEach(all::addAll);
        String message = all.size() == 1 ? all.get(0) : "Please fix the highlighted fields.";
        throw new ValidationException(message, Map.copyOf(fieldErrors), List.copyOf(formErrors));
    }
}
