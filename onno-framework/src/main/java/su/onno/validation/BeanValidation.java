package su.onno.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Jakarta Bean Validation for onno entities: the standard field constraints ({@code @NotNull},
 * {@code @NotBlank}, {@code @Size}, {@code @Min}/{@code @Max}, {@code @DecimalMin}/{@code @DecimalMax},
 * {@code @Pattern}, {@code @Email}, {@code @Positive}, {@code @Past}/{@code @Future}, {@code @Digits},
 * custom {@code ConstraintValidator}s, ...) declared on catalog, document and tabular-section row
 * fields are enforced on the onno write path — before every save and in the form's dry-run
 * {@code /validate} — with each violation recorded in {@link ValidationErrors} under the field it
 * belongs to ({@code name}, {@code items[0].quantity}); a class-level constraint becomes a form-level
 * message.
 *
 * <p>Bean Validation is optional. Without {@code jakarta.validation} on the classpath this class is
 * inert and nothing in onno loads a {@code jakarta.*} type. With the API but no provider (Hibernate
 * Validator), constraints are still read into the metadata and the representable ones are enforced
 * by {@link AttributeValidator}; add {@code spring-boot-starter-validation} for the full semantics.
 * In a Spring application the starter hands the context's {@code jakarta.validation.Validator}
 * to {@link #use(Object)}, so Spring's message source and dependency injection into
 * {@code ConstraintValidator}s apply; elsewhere the default provider is built on first use.</p>
 */
public final class BeanValidation {

    private static final Logger log = LoggerFactory.getLogger(BeanValidation.class);

    private static final boolean API_PRESENT = classPresent("jakarta.validation.Validator");

    /** The validator in use ({@code jakarta.validation.Validator}), or null until resolved. */
    private static volatile Object validator;
    private static volatile boolean defaultResolved;

    private BeanValidation() {
    }

    /** Whether the {@code jakarta.validation} API is on the classpath (annotations are then read). */
    public static boolean apiPresent() {
        return API_PRESENT;
    }

    /**
     * Whether a Bean Validation provider is available, so Jakarta constraints are enforced by it
     * (and {@link AttributeValidator} skips the bounds it derived from them).
     */
    public static boolean isActive() {
        return API_PRESENT && resolve() != null;
    }

    /**
     * Use this {@code jakarta.validation.Validator} (for example the Spring context's) instead of the
     * default provider. {@code null} returns to the default. Typed as {@code Object} so this class
     * carries no {@code jakarta.*} signature for applications without Bean Validation.
     *
     * @throws IllegalArgumentException if {@code jakartaValidator} is not a {@code jakarta.validation.Validator}
     */
    public static void use(Object jakartaValidator) {
        if (jakartaValidator != null && (!API_PRESENT || !JakartaBridge.isValidator(jakartaValidator))) {
            throw new IllegalArgumentException("Not a jakarta.validation.Validator: "
                    + jakartaValidator.getClass().getName());
        }
        validator = jakartaValidator;
        defaultResolved = jakartaValidator != null;
    }

    /**
     * Validate a catalog/document (or any object) and its {@code @TabularSection} rows, recording each
     * violation under its field. Rows are validated even without {@code @Valid} on the section; their
     * violations are keyed {@code section[index].field}, the same paths the request-body checks use.
     * A no-op when Bean Validation is not {@linkplain #isActive() active}.
     */
    public static void validate(Object entity, ValidationErrors errors) {
        if (entity == null || !isActive()) {
            return;
        }
        JakartaBridge.validate(resolve(), entity, errors);
    }

    /**
     * The Jakarta constraints declared on {@code field} in onno's terms, for the metadata scanner;
     * empty when the API is absent or the field declares none of the representable constraints.
     * Framework-internal: applications declare constraints with the Jakarta annotations themselves.
     */
    public static Optional<FieldRules> rulesOf(Field field) {
        return API_PRESENT ? JakartaBridge.rulesOf(field) : Optional.empty();
    }

    /** Whether {@code type} declares any Jakarta constraint on a field (used for a startup warning). */
    public static boolean declaresConstraints(Class<?> type) {
        return API_PRESENT && JakartaBridge.declaresConstraints(type);
    }

    private static Object resolve() {
        Object current = validator;
        if (current != null || defaultResolved) {
            return current;
        }
        synchronized (BeanValidation.class) {
            if (!defaultResolved) {
                validator = JakartaBridge.buildDefault();
                defaultResolved = true;
                if (validator == null) {
                    log.debug("jakarta.validation is on the classpath but no provider is; Jakarta "
                            + "constraints are enforced only where onno can mirror them");
                }
            }
            return validator;
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, BeanValidation.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * The Jakarta constraints of one field that onno mirrors into its metadata. A null bound is
     * "not declared". {@code min}/{@code max} are inclusive bounds only ({@code @Min},
     * {@code @Max}, inclusive {@code @DecimalMin}/{@code @DecimalMax}, {@code @PositiveOrZero},
     * {@code @NegativeOrZero}); exclusive ones ({@code @Positive}, {@code inclusive = false}) are
     * left to the validator so the client never states a bound the server disagrees with.
     *
     * @param required  {@code @NotNull}, {@code @NotBlank} or {@code @NotEmpty}
     * @param mustBeNull {@code @Null}
     * @param maxLength {@code @Size(max)} when bounded
     */
    public record FieldRules(boolean required, boolean mustBeNull, Double min, Double max,
                             Integer minLength, Integer maxLength, String pattern, boolean email) {

        /** Whether any rule is declared. */
        public boolean any() {
            return required || mustBeNull || min != null || max != null || minLength != null
                    || maxLength != null || pattern != null || email;
        }
    }
}
