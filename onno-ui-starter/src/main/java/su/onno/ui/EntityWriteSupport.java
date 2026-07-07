package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.security.SecretCipher;
import su.onno.security.SecretRedactor;
import su.onno.validation.ValidationErrors;

import org.jdbi.v3.core.statement.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

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
}
