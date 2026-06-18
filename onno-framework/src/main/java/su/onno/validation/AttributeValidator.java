package su.onno.validation;

import su.onno.metadata.AttributeDescriptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enforces the declarative {@code @Attribute} constraints (required, string length, numeric
 * min/max, regex pattern, email) on an entity before write, collecting every failure into a
 * {@link ValidationErrors} keyed by field name. Works either against a typed aggregate (values
 * read reflectively) or against a submitted body map (the generic UI write path), so the same
 * rules apply however the write arrives.
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

    private void check(AttributeDescriptor a, Object value, ValidationErrors errors) {
        String label = a.displayName();
        boolean empty = value == null || (value instanceof String s && s.isBlank());

        if (a.required() && empty) {
            errors.field(a.fieldName(), label + " is required");
            return;
        }
        if (empty) {
            return; // optional + absent: nothing else to check
        }

        AttributeDescriptor.Constraints c = a.constraints();
        if (value instanceof String s) {
            if (a.length() > 0 && a.length() <= MAX_VARCHAR && s.length() > a.length()) {
                errors.field(a.fieldName(), label + " must be at most " + a.length() + " characters");
            }
            if (c.minLength() > 0 && s.length() < c.minLength()) {
                errors.field(a.fieldName(), label + " must be at least " + c.minLength() + " characters");
            }
            if (!c.pattern().isBlank() && !Pattern.matches(c.pattern(), s)) {
                errors.field(a.fieldName(), label + " is not in the expected format");
            }
            if (c.email() && !EMAIL.matcher(s).matches()) {
                errors.field(a.fieldName(), label + " must be a valid email address");
            }
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (c.hasMin() && d < c.min()) {
                errors.field(a.fieldName(), label + " must be at least " + number(c.min()));
            }
            if (c.hasMax() && d > c.max()) {
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
