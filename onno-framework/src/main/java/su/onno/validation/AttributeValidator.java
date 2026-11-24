package su.onno.validation;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.AttributeDescriptor.Constraints.Rule;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enforces an attribute's metadata constraints (required, string length, numeric min/max, regex
 * pattern, email) on an entity before write, collecting every failure into a
 * {@link ValidationErrors} keyed by field name. Works either against a typed aggregate (values
 * read reflectively) or against a submitted body map (the generic UI write path), so the same
 * rules apply however the write arrives.
 *
 * <p>The column's storage length is always checked here. A rule that came from a Jakarta annotation
 * ({@link AttributeDescriptor.Constraints#beanValidated()}) is left to {@link BeanValidation} while a
 * provider is active, so it keeps the Jakarta semantics and message and is not reported twice;
 * without a provider this class enforces the representable ones itself.</p>
 */
public final class AttributeValidator {

    // Pragmatic email shape: something@something.tld with no spaces. Not RFC-exhaustive on purpose.
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    // Above this the String column is TEXT (unbounded), so length() isn't a max — skip the check.
    private static final int MAX_VARCHAR = 65_535;

    /** Validate a typed aggregate, reading each attribute's value reflectively. */
    public void validate(Object entity, List<AttributeDescriptor> attributes, ValidationErrors errors) {
        for (AttributeDescriptor a : attributes) {
            check(a, read(entity, a.fieldName()), errors);
        }
    }

    /** Validate a submitted body map keyed by attribute field name (the generic UI write path). */
    public void validate(Map<String, Object> values, List<AttributeDescriptor> attributes, ValidationErrors errors) {
        for (AttributeDescriptor a : attributes) {
            check(a, values.get(a.fieldName()), errors);
        }
    }

    /**
     * Validate only the attributes a partial-update body actually submits. Updates touch just the
     * keys present in the body, so an absent required attribute is "left unchanged", not "cleared" —
     * flagging it would force clients to resend every required field on every write. A key that IS
     * present with a null/blank value is still a violation (that write would clear the column).
     */
    public void validatePartial(Map<String, Object> values, List<AttributeDescriptor> attributes,
                                ValidationErrors errors) {
        for (AttributeDescriptor a : attributes) {
            if (values.containsKey(a.fieldName())) {
                check(a, values.get(a.fieldName()), errors);
            }
        }
    }

    private void check(AttributeDescriptor a, Object value, ValidationErrors errors) {
        String label = a.displayName();
        boolean empty = value == null || (value instanceof String s && s.isBlank());
        AttributeDescriptor.Constraints c = a.constraints();
        boolean delegate = BeanValidation.isActive();

        if (a.required() && empty) {
            if (!(delegate && c.beanValidated(Rule.REQUIRED))) {
                errors.field(a.fieldName(), label + " is required");
            }
            return;
        }
        if (empty) {
            return; // optional + absent: nothing else to check
        }

        if (value instanceof String s) {
            int limit = a.length() > 0 && a.length() <= MAX_VARCHAR ? a.length() : 0;
            if (c.hasMaxLength()) {
                // @Size(max) never exceeds the column (checked at startup): the provider reporting it
                // covers the column too; otherwise the tighter of the two is the limit.
                limit = delegate && c.beanValidated(Rule.MAX_LENGTH) ? 0
                        : limit == 0 ? c.maxLength() : Math.min(limit, c.maxLength());
            }
            if (limit > 0 && s.length() > limit) {
                errors.field(a.fieldName(), label + " must be at most " + limit + " characters");
            }
            if (c.minLength() > 0 && s.length() < c.minLength() && !(delegate && c.beanValidated(Rule.MIN_LENGTH))) {
                errors.field(a.fieldName(), label + " must be at least " + c.minLength() + " characters");
            }
            if (!c.pattern().isBlank() && !(delegate && c.beanValidated(Rule.PATTERN))
                    && !Pattern.matches(c.pattern(), s)) {
                errors.field(a.fieldName(), label + " is not in the expected format");
            }
            if (c.email() && !(delegate && c.beanValidated(Rule.EMAIL)) && !EMAIL.matcher(s).matches()) {
                errors.field(a.fieldName(), label + " must be a valid email address");
            }
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (c.hasMin() && d < c.min() && !(delegate && c.beanValidated(Rule.MIN))) {
                errors.field(a.fieldName(), label + " must be at least " + number(c.min()));
            }
            if (c.hasMax() && d > c.max() && !(delegate && c.beanValidated(Rule.MAX))) {
                errors.field(a.fieldName(), label + " must be at most " + number(c.max()));
            }
        }
    }

    /** Render a bound without a trailing ".0" when it's whole. */
    private static String number(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static Object read(Object entity, String fieldName) {
        Class<?> current = entity.getClass();
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(entity);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                // setAccessible(true) succeeded above, so this signals a real access problem
                // (e.g. a SecurityManager / module restriction) — surface it rather than masking
                // the field as null and silently skipping its validation.
                throw new IllegalStateException("Cannot read field '" + fieldName + "' on "
                        + entity.getClass().getName(), e);
            }
        }
        return null;
    }
}
