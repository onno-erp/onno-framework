package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.EnumerationValueDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.types.Ref;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the seed row for a <em>new</em>-entity form so a domain field initializer pre-fills it
 * (issue #181). A blank New form ignored declared defaults — {@code private OrderStatus status =
 * OrderStatus.NEW} never reached the UI because no instance was ever constructed. Here we build a
 * fresh instance via the no-arg constructor (running those initializers), then serialize each
 * attribute's resulting value into the same column-keyed, loose JSON shape the read path emits:
 * {@link Ref}/enum → its id {@link UUID}, temporals → ISO string, everything else verbatim. The
 * caller resolves the ref/enum columns afterwards (so a default's label shows), exactly like an
 * edit form's initial row from {@code CatalogQueryService.get}.
 *
 * <p>This mirrors the loose-value conversion {@link WriteLifecycle#looseValue} runs on the write
 * path — kept separate so seeding a form (read side) doesn't drag in the secret cipher and write
 * hooks. Only values a plain field initializer can express are seeded; a {@code Ref} default (whose
 * id isn't known at compile time) can't be a literal initializer, so it stays unset here.
 */
final class NewEntityDefaults {

    private NewEntityDefaults() {
    }

    /**
     * Column-keyed default values from a fresh instance of {@code javaClass}, skipping nulls (a
     * field with no initializer) and secrets (a secret never carries an exposable default). An
     * unconstructable class (no usable no-arg constructor) yields an empty map — the New form is
     * blank, exactly as before.
     */
    static Map<String, Object> columnValues(Class<?> javaClass, List<AttributeDescriptor> attributes,
                                            MetadataRegistry registry) {
        Map<String, Object> row = new LinkedHashMap<>();
        Object entity = instantiate(javaClass);
        if (entity == null) {
            return row;
        }
        for (AttributeDescriptor attr : attributes) {
            if (attr.secret()) {
                continue;
            }
            Object value = looseValue(readField(entity, attr.fieldName()), registry);
            if (value != null) {
                row.put(attr.columnName(), value);
            }
        }
        return row;
    }

    private static Object instantiate(Class<?> javaClass) {
        try {
            var ctor = javaClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException cannotBuild) {
            return null;
        }
    }

    /** A field value in the loose JSON-ish shape the form's {@code initial} row uses. */
    private static Object looseValue(Object value, MetadataRegistry registry) {
        if (value == null) {
            return null;
        }
        if (value instanceof Ref<?> ref) {
            return ref.id();
        }
        if (value instanceof Enum<?> constant) {
            return enumId(constant, registry);
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        return value;
    }

    /** The stored id of an enum constant (enums persist as the enumeration value's UUID). */
    private static UUID enumId(Enum<?> constant, MetadataRegistry registry) {
        EnumerationDescriptor desc = registry.allEnumerations().stream()
                .filter(e -> e.javaClass().equals(constant.getDeclaringClass()))
                .findFirst()
                .orElse(null);
        if (desc == null) {
            return null;
        }
        return desc.values().stream()
                .filter(v -> v.name().equals(constant.name()))
                .map(EnumerationValueDescriptor::id)
                .findFirst()
                .orElse(null);
    }

    private static Object readField(Object target, String fieldName) {
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
