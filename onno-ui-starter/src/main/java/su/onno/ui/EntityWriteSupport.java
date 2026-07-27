package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.security.SecretCipher;
import su.onno.security.SecretRedactor;
import su.onno.validation.ValidationErrors;
import su.onno.types.PolyRef;

import org.jdbi.v3.core.statement.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.Map;

final class EntityWriteSupport {

    private EntityWriteSupport() {
    }

    static void bindAttribute(Update update, AttributeDescriptor attr, Object value, SecretCipher secretCipher) {
        SqlBind.nullable(update, attr.columnName(), coerceAttribute(attr, value, secretCipher));
    }

    static boolean leaveSecretUnchanged(AttributeDescriptor attr, Object value) {
        return attr.secret() && SecretRedactor.SET.equals(value);
    }

    static boolean bake(WriteLifecycle lifecycle, Object entity, boolean isNew, ValidationErrors errors) {
        try {
            lifecycle.runHooks(entity, isNew);
            lifecycle.collectRules(entity, errors);
            return true;
        } catch (RuntimeException failed) {
            if (errors.isEmpty()) {
                throw failed;
            }
            return false;
        }
    }

    /**
     * The write lifecycle for a dry-run validate: same hooks and rules as {@link #bake}, but a hook
     * crashing is swallowed rather than surfaced. Live validation runs against half-filled forms,
     * where a {@code beforeWrite()} that divides by a not-yet-entered quantity is routine — the save
     * path still surfaces it for real.
     */
    static void dryRunRules(WriteLifecycle lifecycle, Object entity, boolean isNew, ValidationErrors errors) {
        try {
            lifecycle.runHooks(entity, isNew);
            lifecycle.collectRules(entity, errors);
        } catch (RuntimeException hookCrashedOnPartialInput) {
            // report what was collected; the actual write re-runs and surfaces this
        }
    }

    /** The JSON body of a dry-run validate: always 200, with the outcome in the payload. */
    static java.util.Map<String, Object> validationReport(ValidationErrors errors) {
        java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("valid", errors.isEmpty());
        report.put("fieldErrors", errors.fieldErrors());
        report.put("formErrors", errors.formErrors());
        return report;
    }

    static void requireWritable(UiProperties properties) {
        if (properties.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "UI is in read-only mode");
        }
    }

    private static Object coerceAttribute(AttributeDescriptor attr, Object value, SecretCipher secretCipher) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (attr.secret()) {
            return SecretRedactor.SET.equals(value) ? null : secretCipher.encrypt(value.toString());
        }
        if (attr.isPolymorphicRef()) {
            PolyRef ref = polymorphicRef(attr, value);
            boolean allowed = attr.refTargets().stream()
                    .anyMatch(target -> target.javaTypeName().equals(ref.type().getName()));
            if (!allowed) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Reference type " + ref.type().getName()
                                + " is not allowed for " + attr.fieldName());
            }
            return ref.externalForm();
        }
        if (attr.isRef() || attr.javaType().isEnum()) {
            return value instanceof UUID u ? u : UUID.fromString(value.toString());
        }
        if (attr.javaType() == BigDecimal.class) {
            return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
        }
        Object temporal = TemporalValues.coerce(attr.javaType(), value);
        if (temporal != null) {
            return temporal;
        }
        return value;
    }

    private static PolyRef polymorphicRef(AttributeDescriptor attr, Object value) {
        if (value instanceof PolyRef ref) return ref;
        if (value instanceof Map<?, ?> map) {
            Object typeValue = map.get("type");
            Object idValue = map.get("id");
            if (typeValue == null || idValue == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Polymorphic reference requires type and id");
            }
            String type = typeValue.toString();
            String javaType = attr.refTargets().stream()
                    .filter(target -> target.javaTypeName().equals(type)
                            || target.logicalName().equals(type))
                    .map(su.onno.metadata.ReferenceTargetDescriptor::javaTypeName)
                    .findFirst()
                    .orElse(type);
            return PolyRef.parse(javaType + "|" + idValue);
        }
        return PolyRef.parse(value.toString());
    }
}
