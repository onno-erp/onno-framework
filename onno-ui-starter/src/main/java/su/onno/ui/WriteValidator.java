package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.rules.BusinessRuleValidator;
import su.onno.types.Ref;
import su.onno.validation.AttributeValidator;
import su.onno.validation.ValidationErrors;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates a generic catalog/document write before it persists. The generic controllers insert
 * via JDBI (not a Spring Data repository), so the lifecycle {@code BeforeConvertCallback} — where
 * entity validation otherwise runs — never fires for them. This re-applies the same checks against
 * the submitted body: declarative {@code @Attribute} constraints, plus any
 * {@link su.onno.rules.BusinessRule}s run on a best-effort transient entity built from the body.
 * Failures throw a field-mapped {@link su.onno.validation.ValidationException} (→ HTTP 422).
 */
public class WriteValidator {

    private final AttributeValidator attributeValidator = new AttributeValidator();
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();

    public void validate(Class<?> entityClass, List<AttributeDescriptor> attributes, Map<String, Object> body) {
        ValidationErrors errors = new ValidationErrors();
        attributeValidator.validate(body, attributes, errors);
        Object entity = tryBuild(entityClass, attributes, body);
        if (entity != null) {
            businessRuleValidator.collect(entity, errors);
        }
        errors.throwIfAny();
    }

    /**
     * Build a transient entity from the body so {@code Validated.rules()} can run on the UI write
     * path. Unbindable values (e.g. enums, which the form sends as opaque ids) are left at their
     * defaults; if the entity can't be built at all, business rules are skipped rather than
     * blocking the save — the declarative constraints still apply.
     */
    private Object tryBuild(Class<?> entityClass, List<AttributeDescriptor> attributes, Map<String, Object> body) {
        try {
            var ctor = entityClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object entity = ctor.newInstance();
            for (AttributeDescriptor a : attributes) {
                Object raw = body.get(a.fieldName());
                if (raw == null || "".equals(raw)) {
                    continue;
                }
                Field field = findField(entityClass, a.fieldName());
                if (field == null) {
                    continue;
                }
                Object coerced = coerce(field.getType(), raw);
                if (coerced != null) {
                    field.setAccessible(true);
                    field.set(entity, coerced);
                }
            }
            return entity;
        } catch (Exception cannotBuild) {
            return null;
        }
    }

    private static Object coerce(Class<?> type, Object raw) {
        try {
            String s = raw.toString().trim();
            if (type == String.class) {
                return raw.toString();
            }
            if (type == Integer.class || type == int.class) {
                return raw instanceof Number n ? n.intValue() : Integer.valueOf(s);
            }
            if (type == Long.class || type == long.class) {
                return raw instanceof Number n ? n.longValue() : Long.valueOf(s);
            }
            if (type == Double.class || type == double.class) {
                return raw instanceof Number n ? n.doubleValue() : Double.valueOf(s);
            }
            if (type == Float.class || type == float.class) {
                return raw instanceof Number n ? n.floatValue() : Float.valueOf(s);
            }
            if (type == BigDecimal.class) {
                return raw instanceof BigDecimal bd ? bd : new BigDecimal(s);
            }
            if (type == Boolean.class || type == boolean.class) {
                return raw instanceof Boolean b ? b : Boolean.valueOf(s);
            }
            if (type == UUID.class) {
                return raw instanceof UUID u ? u : UUID.fromString(s);
            }
            if (type == LocalDate.class) {
                return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
            }
            if (type == LocalDateTime.class) {
                return LocalDateTime.parse(s.length() >= 16 ? s.substring(0, 16) : s);
            }
            if (Ref.class.isAssignableFrom(type)) {
                // The concrete target type is erased; Object.class suffices for null/id checks.
                return new Ref<>(Object.class, raw instanceof UUID u ? u : UUID.fromString(s));
            }
            return null; // enums and anything else: leave at default
        } catch (Exception unbindable) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
