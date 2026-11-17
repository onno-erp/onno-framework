package su.onno.service;

import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.ReferenceTargetDescriptor;
import su.onno.model.TabularSectionRow;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.rules.BusinessRuleValidator;
import su.onno.security.SecretRedactor;
import su.onno.types.PolyRef;
import su.onno.types.Ref;
import su.onno.validation.ValidationErrors;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps a generic write's request body onto the typed catalog/document it describes, so the command
 * services can save that entity through {@link EntityStore} like any typed repository caller.
 * The body uses the API's loose shapes — a {@link Ref} or enum as its id, a {@link PolyRef} as
 * {@code "type|id"} or {@code {type, id}}, temporals as ISO strings — and each present attribute is
 * coerced to its field type. A value that does not coerce becomes a field error rather than being
 * dropped; a secret sent as the read-side "set" sentinel keeps the stored value.
 *
 * <p>It also runs the pre-save hooks for a dry-run validate, which never reaches the store.
 */
class EntityBodyMapper {

    private final MetadataRegistry registry;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();

    EntityBodyMapper(MetadataRegistry registry) {
        this.registry = registry;
    }

    /** A new instance of an entity or row class through its no-arg constructor. */
    static <T> T instantiate(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " needs a no-arg constructor to be written"
                    + " through the generic API", e);
        }
    }

    /** Set each attribute present in {@code body} on {@code entity}, recording coercion failures. */
    void applyBody(Object entity, List<AttributeDescriptor> attributes, Map<String, Object> body,
                   ValidationErrors errors) {
        for (AttributeDescriptor attr : attributes) {
            if (!body.containsKey(attr.fieldName())) {
                continue;
            }
            Object raw = body.get(attr.fieldName());
            if (attr.secret() && SecretRedactor.SET.equals(raw)) {
                continue;
            }
            try {
                setField(entity, attr, raw);
            } catch (ServiceException refused) {
                throw refused;
            } catch (RuntimeException badValue) {
                String temporal = TemporalValues.writeMessage(attr.javaType());
                errors.field(attr.fieldName(), temporal != null
                        ? attr.displayName() + " " + temporal
                        : attr.displayName() + " is not a valid value");
            }
        }
    }

    /**
     * Replace each tabular section present in {@code body} with rows built from it. Rows are new
     * objects: the save replaces a section's lines, as the API always has.
     */
    @SuppressWarnings("unchecked")
    void applySections(Object entity, List<TabularSectionDescriptor> sections, Map<String, Object> body,
                       ValidationErrors errors) {
        for (TabularSectionDescriptor ts : sections) {
            if (!(body.get(ts.name()) instanceof List<?> rows)) {
                continue;
            }
            List<TabularSectionRow> built = new ArrayList<>();
            for (Object rawRow : rows) {
                if (rawRow instanceof Map<?, ?> rowBody) {
                    TabularSectionRow row;
                    try {
                        row = (TabularSectionRow) instantiate(ts.rowClass());
                    } catch (IllegalStateException | ExceptionInInitializerError cannotBuild) {
                        throw new IllegalStateException(
                                "Cannot construct row for tabular section '" + ts.name() + "'", cannotBuild);
                    }
                    applyBody(row, ts.attributes(), (Map<String, Object>) rowBody, errors);
                    built.add(row);
                }
            }
            set(requireField(entity.getClass(), ts.fieldName()), entity, built);
        }
    }

    /**
     * The pre-save lifecycle for a dry-run validate: {@code onFilling()} (new only), then
     * {@code beforeWrite()}, then the business rules on the result. A hook crashing on a half-filled
     * form is swallowed — the real save runs it again and surfaces the failure.
     */
    void dryRun(Object entity, boolean isNew, ValidationErrors errors) {
        try {
            if (isNew && entity instanceof OnFillingHandler handler) {
                handler.onFilling();
            }
            if (entity instanceof BeforeWriteHandler handler) {
                handler.beforeWrite();
            }
            businessRuleValidator.collect(entity, errors);
        } catch (RuntimeException hookCrashedOnPartialInput) {
            // report what was collected; the actual write re-runs and surfaces this
        }
    }

    @SuppressWarnings("unchecked")
    private void setField(Object target, AttributeDescriptor attr, Object value) {
        Field field = findField(target.getClass(), attr.fieldName());
        if (field == null) {
            return;
        }
        Class<?> type = field.getType();
        if (value == null || "".equals(value)) {
            if (!type.isPrimitive()) {
                set(field, target, null);
            }
            return;
        }
        Object typed;
        if (PolyRef.class.isAssignableFrom(type)) {
            typed = polymorphicRef(attr, value);
        } else if (Ref.class.isAssignableFrom(type)) {
            if (!(field.getGenericType() instanceof ParameterizedType pt)) {
                return;
            }
            typed = Ref.of((Class<?>) pt.getActualTypeArguments()[0], toUuid(value));
        } else if (type == BigDecimal.class) {
            typed = value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
        } else if (type == String.class) {
            typed = value.toString();
        } else if (type == int.class || type == Integer.class) {
            typed = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        } else if (type == long.class || type == Long.class) {
            typed = value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
        } else if (type == double.class || type == Double.class) {
            typed = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
        } else if (type == boolean.class || type == Boolean.class) {
            typed = value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
        } else if (type == LocalDate.class) {
            typed = TemporalValues.toLocalDate(value);
        } else if (type == LocalDateTime.class) {
            typed = TemporalValues.toWriteLocalDateTime(value);
        } else if (type == java.time.LocalTime.class) {
            typed = TemporalValues.toLocalTime(value);
        } else if (type == java.time.Instant.class) {
            typed = TemporalValues.toWriteInstant(value);
        } else if (type == UUID.class) {
            typed = toUuid(value);
        } else if (type.isEnum()) {
            Optional<? extends Enum<?>> constant = enumConstant((Class<? extends Enum<?>>) type, toUuid(value));
            if (constant.isEmpty()) {
                throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value " + value);
            }
            typed = constant.get();
        } else {
            typed = value;
        }
        set(field, target, typed);
    }

    /**
     * A polymorphic reference from {@code "type|id"}, a {@link PolyRef}, or {@code {type, id}} where
     * the type may be the target's logical name. The type must be one the attribute allows.
     */
    private static PolyRef polymorphicRef(AttributeDescriptor attr, Object value) {
        PolyRef ref;
        if (value instanceof PolyRef given) {
            ref = given;
        } else if (value instanceof Map<?, ?> map) {
            Object typeValue = map.get("type");
            Object idValue = map.get("id");
            if (typeValue == null || idValue == null) {
                throw new ServiceException(ServiceException.Status.BAD_REQUEST,
                        "Polymorphic reference requires type and id");
            }
            String type = typeValue.toString();
            String javaType = attr.refTargets().stream()
                    .filter(target -> target.javaTypeName().equals(type) || target.logicalName().equals(type))
                    .map(ReferenceTargetDescriptor::javaTypeName)
                    .findFirst()
                    .orElse(type);
            ref = PolyRef.parse(javaType + "|" + idValue);
        } else {
            ref = PolyRef.parse(value.toString());
        }
        boolean allowed = attr.refTargets().stream()
                .anyMatch(target -> target.javaTypeName().equals(ref.type().getName()));
        if (!allowed) {
            throw new ServiceException(ServiceException.Status.BAD_REQUEST,
                    "Reference type " + ref.type().getName() + " is not allowed for " + attr.fieldName());
        }
        return ref;
    }

    private Optional<? extends Enum<?>> enumConstant(Class<? extends Enum<?>> type, UUID id) {
        return registry.allEnumerations().stream()
                .filter(e -> e.javaClass().equals(type))
                .map(EnumerationDescriptor::values)
                .flatMap(List::stream)
                .filter(v -> v.id().equals(id))
                .map(v -> enumByName(type, v.name()))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private static Enum<?> enumByName(Class<? extends Enum<?>> type, String name) {
        for (Enum<?> constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static Field requireField(Class<?> type, String name) {
        Field field = findField(type, name);
        if (field == null) {
            throw new IllegalStateException("Missing field " + name + " on " + type.getName());
        }
        return field;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // continue through inherited business fields
            }
        }
        return null;
    }
}
