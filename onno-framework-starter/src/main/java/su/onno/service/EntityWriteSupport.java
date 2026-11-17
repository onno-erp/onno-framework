package su.onno.service;

import su.onno.metadata.AttributeDescriptor;
import su.onno.security.SecretCipher;
import su.onno.security.SecretRedactor;
import su.onno.validation.ValidationException;
import su.onno.validation.ValidationErrors;
import su.onno.types.PolyRef;

import org.jdbi.v3.core.statement.Update;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.Map;

public final class EntityWriteSupport {

    private EntityWriteSupport() {
    }

    /** The JSON body of a dry-run validate: always 200, with the outcome in the payload. */
    static java.util.Map<String, Object> validationReport(ValidationErrors errors) {
        java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("valid", errors.isEmpty());
        report.put("fieldErrors", errors.fieldErrors());
        report.put("formErrors", errors.formErrors());
        return report;
    }

    static void requireWritable(WebProperties properties) {
        if (properties.isReadOnly()) {
            throw new ServiceException(ServiceException.Status.FORBIDDEN, "The application is in read-only mode");
        }
    }
}
