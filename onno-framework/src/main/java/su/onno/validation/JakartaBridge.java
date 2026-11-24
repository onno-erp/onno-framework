package su.onno.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.NoProviderFoundException;
import jakarta.validation.Path;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.onno.annotations.TabularSection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only class in onno that links against {@code jakarta.validation}. {@link BeanValidation}
 * loads it only after checking the API is on the classpath, so applications without Bean
 * Validation never resolve a {@code jakarta.*} type.
 */
final class JakartaBridge {

    private static final Logger log = LoggerFactory.getLogger(JakartaBridge.class);

    private JakartaBridge() {
    }

    static boolean isValidator(Object candidate) {
        return candidate instanceof Validator;
    }

    /** The default provider's validator, or null when no provider is on the classpath. */
    static Object buildDefault() {
        try {
            return Validation.buildDefaultValidatorFactory().getValidator();
        } catch (NoProviderFoundException noProvider) {
            return null;
        } catch (RuntimeException | LinkageError interpolationUnavailable) {
            // Hibernate Validator's default message interpolator needs Jakarta EL; without it, fall
            // back to its EL-free interpolator rather than switching validation off.
            try {
                Class<?> type = Class.forName(
                        "org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator");
                MessageInterpolator interpolator = (MessageInterpolator) type.getDeclaredConstructor().newInstance();
                return Validation.byDefaultProvider().configure().messageInterpolator(interpolator)
                        .buildValidatorFactory().getValidator();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError fallbackFailed) {
                log.warn("Bean Validation provider could not be started; Jakarta constraints are "
                        + "enforced only where onno can mirror them", interpolationUnavailable);
                return null;
            }
        }
    }

    static void validate(Object validatorObject, Object entity, ValidationErrors errors) {
        Validator validator = (Validator) validatorObject;
        Map<String, Field> sections = sectionFields(entity.getClass());
        for (ConstraintViolation<Object> violation : validator.validate(entity)) {
            record(violation, "", sections, errors);
        }
        // Rows are validated explicitly, so a section needs no @Valid and its paths match the
        // request-body checks (section name, not Java field name).
        for (Map.Entry<String, Field> section : sections.entrySet()) {
            if (!(read(section.getValue(), entity) instanceof List<?> rows)) {
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                Object row = rows.get(i);
                if (row == null) {
                    continue;
                }
                String prefix = section.getKey() + "[" + i + "]";
                for (ConstraintViolation<Object> violation : validator.validate(row)) {
                    record(violation, prefix, Map.of(), errors);
                }
            }
        }
    }

    private static void record(ConstraintViolation<Object> violation, String prefix,
                               Map<String, Field> sections, ValidationErrors errors) {
        String first = null;
        int nodes = 0;
        for (Path.Node node : violation.getPropertyPath()) {
            if (nodes++ == 0) {
                first = node.getName();
            }
        }
        if (first == null) { // class-level constraint
            errors.field(prefix, violation.getMessage()); // blank prefix = form-level
            return;
        }
        String section = sectionName(sections, first);
        if (section != null && nodes > 1) {
            return; // cascaded into a row via @Valid: already reported by the explicit row pass
        }
        String field = section != null ? section : first;
        errors.field(prefix.isEmpty() ? field : prefix + "." + field, violation.getMessage());
    }

    private static String sectionName(Map<String, Field> sections, String fieldName) {
        for (Map.Entry<String, Field> section : sections.entrySet()) {
            if (section.getValue().getName().equals(fieldName)) {
                return section.getKey();
            }
        }
        return null;
    }

    /** {@code @TabularSection} fields by section name (the request/metadata key). */
    private static Map<String, Field> sectionFields(Class<?> type) {
        Map<String, Field> result = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                TabularSection section = field.getAnnotation(TabularSection.class);
                if (section != null) {
                    result.putIfAbsent(section.name().isEmpty() ? field.getName() : section.name(), field);
                }
            }
        }
        return result;
    }

    private static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException("Cannot read tabular section '" + field.getName() + "' on "
                    + target.getClass().getName(), e);
        }
    }

    static Optional<BeanValidation.FieldRules> rulesOf(Field field) {
        boolean text = CharSequence.class.isAssignableFrom(field.getType());
        boolean required = active(field.getAnnotation(NotNull.class))
                || active(field.getAnnotation(NotBlank.class))
                || active(field.getAnnotation(NotEmpty.class));
        boolean mustBeNull = active(field.getAnnotation(Null.class));

        Double min = null;
        Double max = null;
        Min minAnn = field.getAnnotation(Min.class);
        if (active(minAnn)) min = stricterMin(min, (double) minAnn.value());
        DecimalMin decimalMin = field.getAnnotation(DecimalMin.class);
        if (active(decimalMin) && decimalMin.inclusive()) min = stricterMin(min, Double.parseDouble(decimalMin.value()));
        if (active(field.getAnnotation(PositiveOrZero.class))) min = stricterMin(min, 0d);
        Max maxAnn = field.getAnnotation(Max.class);
        if (active(maxAnn)) max = stricterMax(max, (double) maxAnn.value());
        DecimalMax decimalMax = field.getAnnotation(DecimalMax.class);
        if (active(decimalMax) && decimalMax.inclusive()) max = stricterMax(max, Double.parseDouble(decimalMax.value()));
        if (active(field.getAnnotation(NegativeOrZero.class))) max = stricterMax(max, 0d);

        Integer minLength = null;
        Integer maxLength = null;
        Size size = field.getAnnotation(Size.class);
        if (text && active(size)) {
            if (size.min() > 0) minLength = size.min();
            if (size.max() < Integer.MAX_VALUE) maxLength = size.max();
        }
        String pattern = null;
        Pattern patternAnn = field.getAnnotation(Pattern.class);
        // A pattern with flags would not mean the same thing to a client regex; leave it to the validator.
        if (text && active(patternAnn) && patternAnn.flags().length == 0) pattern = patternAnn.regexp();
        boolean email = text && active(field.getAnnotation(Email.class));

        BeanValidation.FieldRules rules = new BeanValidation.FieldRules(
                required, mustBeNull, min, max, minLength, maxLength, pattern, email);
        return rules.any() ? Optional.of(rules) : Optional.empty();
    }

    static boolean declaresConstraints(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (hasConstraint(current.getAnnotations())) return true;
            for (Field field : current.getDeclaredFields()) {
                if (hasConstraint(field.getAnnotations())) return true;
            }
        }
        return false;
    }

    private static boolean hasConstraint(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    /** Declared and in the default group (a constraint in another group never runs on the write path). */
    private static boolean active(Annotation annotation) {
        if (annotation == null) return false;
        try {
            Object groups = annotation.annotationType().getMethod("groups").invoke(annotation);
            return groups instanceof Class<?>[] array
                    && (array.length == 0 || java.util.Arrays.asList(array).contains(Default.class));
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private static Double stricterMin(Double current, double candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }

    private static Double stricterMax(Double current, double candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }
}
