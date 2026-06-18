package su.onno.ui;

import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.EnumerationValueDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.rules.BusinessRuleValidator;
import su.onno.security.SecretCipher;
import su.onno.security.SecretRedactor;
import su.onno.types.Ref;
import su.onno.validation.ValidationErrors;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs the entity-level write lifecycle for the generic command path so it matches the repository
 * path. The catalog/document command services persist with hand-built JDBI INSERT/UPDATEs rather
 * than a Spring Data repository, so the {@code BeforeConvertCallback} that drives the lifecycle on
 * the repository path never fires for them (issue #158). Left unaddressed, {@link OnFillingHandler},
 * {@link BeforeWriteHandler} and cross-field {@link su.onno.rules.Validated} rules are skipped, and
 * fields a model derives in {@code beforeWrite()} stay null on anything created or edited through
 * the REST API, the generated UI, or CSV import.
 *
 * <p>This rebuilds the typed entity at the same fidelity the runtime uses (enums resolved by id,
 * {@link Ref}s typed, secrets in plaintext), runs {@code onFilling()} (create only) and
 * {@code beforeWrite()}, collects business-rule failures on the final state, and writes any field a
 * hook derived back into the submitted body so the JDBI write captures it. Auto-numbering and
 * secret encryption stay with the command services, which own those concerns.
 */
class WriteLifecycle {

    private final MetadataRegistry registry;
    private final SecretCipher secretCipher;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();

    WriteLifecycle(MetadataRegistry registry, SecretCipher secretCipher) {
        this.registry = registry;
        this.secretCipher = secretCipher;
    }

    /** Run {@code onFilling()} (new entities only) then {@code beforeWrite()}. */
    void runHooks(Object entity, boolean isNew) {
        if (isNew && entity instanceof OnFillingHandler handler) {
            handler.onFilling();
        }
        if (entity instanceof BeforeWriteHandler handler) {
            handler.beforeWrite();
        }
    }

    /** Collect business-rule failures on the final entity state into {@code errors}. */
    void collectRules(Object entity, ValidationErrors errors) {
        businessRuleValidator.collect(entity, errors);
    }

    /**
     * Overlay the submitted body onto {@code entity}: set each attribute present in the body, at
     * runtime fidelity. A secret whose value is the read-side "set" sentinel is left untouched so a
     * loaded ciphertext is preserved. A value that cannot be coerced to its field type is recorded
     * as a field error rather than silently dropped.
     */
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
                setField(entity, attr, raw, false);
            } catch (RuntimeException badValue) {
                errors.field(attr.fieldName(), attr.displayName() + " is not a valid value");
            }
        }
    }

    /** Populate an attribute field from a stored DB value (secrets are ciphertext → decrypted). */
    void setFromStored(Object entity, AttributeDescriptor attr, Object stored) {
        try {
            setField(entity, attr, stored, true);
        } catch (RuntimeException lenient) {
            // A stored value that no longer coerces (e.g. a since-removed enum constant) is left at
            // its default rather than failing the whole load.
        }
    }

    /** Snapshot the non-secret attribute values so a hook-derived change can be detected later. */
    Map<String, Object> snapshot(Object entity, List<AttributeDescriptor> attributes) {
        Map<String, Object> values = new HashMap<>();
        for (AttributeDescriptor attr : attributes) {
            if (!attr.secret()) {
                values.put(attr.fieldName(), readField(entity, attr.fieldName()));
            }
        }
        return values;
    }

    /**
     * Merge the attributes a hook changed since {@code snapshot} back into the body, in the loose
     * shape the body uses ({@link Ref} → id, enum → id, temporals → ISO string), so the JDBI write
     * captures derived fields without disturbing values the caller did not change. Secret attributes
     * are left to the caller's original body so the existing write-only handling is preserved.
     */
    void writeBackDerived(Object entity, List<AttributeDescriptor> attributes,
                          Map<String, Object> snapshot, Map<String, Object> body) {
        for (AttributeDescriptor attr : attributes) {
            if (attr.secret()) {
                continue;
            }
            Object current = readField(entity, attr.fieldName());
            if (!Objects.equals(current, snapshot.get(attr.fieldName()))) {
                body.put(attr.fieldName(), looseValue(current));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void setField(Object target, AttributeDescriptor attr, Object value, boolean fromCiphertext) {
        Field field = findField(target.getClass(), attr.fieldName());
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        Class<?> type = field.getType();
        try {
            if (value == null || "".equals(value)) {
                if (!type.isPrimitive()) {
                    field.set(target, null);
                }
                return;
            }

            if (Ref.class.isAssignableFrom(type)) {
                if (field.getGenericType() instanceof ParameterizedType pt) {
                    Class<?> refTarget = (Class<?>) pt.getActualTypeArguments()[0];
                    field.set(target, Ref.of(refTarget, toUuid(value)));
                }
            } else if (type == BigDecimal.class) {
                field.set(target, value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString()));
            } else if (type == String.class) {
                field.set(target, attr.secret() && fromCiphertext
                        ? secretCipher.decrypt(value.toString()) : value.toString());
            } else if (type == int.class || type == Integer.class) {
                field.set(target, value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString()));
            } else if (type == long.class || type == Long.class) {
                field.set(target, value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()));
            } else if (type == double.class || type == Double.class) {
                field.set(target, value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString()));
            } else if (type == boolean.class || type == Boolean.class) {
                field.set(target, value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString()));
            } else if (type == LocalDate.class) {
                field.set(target, DocumentCommandService.toLocalDate(value));
            } else if (type == LocalDateTime.class) {
                field.set(target, DocumentCommandService.toLocalDateTime(value));
            } else if (type == UUID.class) {
                field.set(target, toUuid(value));
            } else if (type.isEnum()) {
                enumConstant((Class<? extends Enum<?>>) type, toUuid(value))
                        .ifPresent(constant -> setQuietly(field, target, constant));
            } else {
                field.set(target, value);
            }
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private Object readField(Object target, String fieldName) {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** Convert an entity field value to the loose JSON-ish shape the JDBI bind layer expects. */
    private Object looseValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Ref<?> ref) {
            return ref.id();
        }
        if (value instanceof Enum<?> constant) {
            return enumId(constant);
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        return value;
    }

    private UUID enumId(Enum<?> constant) {
        EnumerationDescriptor desc = enumDescriptor(constant.getClass());
        if (desc == null) {
            return null;
        }
        return desc.values().stream()
                .filter(v -> v.name().equals(constant.name()))
                .map(EnumerationValueDescriptor::id)
                .findFirst()
                .orElse(null);
    }

    private java.util.Optional<? extends Enum<?>> enumConstant(Class<? extends Enum<?>> type, UUID id) {
        EnumerationDescriptor desc = enumDescriptor(type);
        if (desc == null) {
            return java.util.Optional.empty();
        }
        return desc.values().stream()
                .filter(v -> v.id().equals(id))
                .map(v -> enumByName(type, v.name()))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private EnumerationDescriptor enumDescriptor(Class<?> type) {
        return registry.allEnumerations().stream()
                .filter(e -> e.javaClass().equals(type))
                .findFirst()
                .orElse(null);
    }

    private static Enum<?> enumByName(Class<? extends Enum<?>> type, String name) {
        Enum<?>[] constants = type.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (Enum<?> constant : constants) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    private static void setQuietly(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
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
