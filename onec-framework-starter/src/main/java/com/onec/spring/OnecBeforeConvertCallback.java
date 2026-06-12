package com.onec.spring;

import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.lifecycle.OnFillingHandler;
import com.onec.metadata.*;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.model.TabularSectionRow;
import com.onec.numbering.NumberGenerator;
import com.onec.performance.OnecPerformance;
import com.onec.rules.BusinessRuleValidator;
import com.onec.security.SecretCipher;
import com.onec.security.SecretFields;
import com.onec.validation.AttributeValidator;
import com.onec.validation.ValidationErrors;

import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

public class OnecBeforeConvertCallback implements BeforeConvertCallback<Object> {

    private final MetadataRegistry registry;
    private final NumberGenerator numberGenerator;
    private final SecretCipher secretCipher;
    private final OnecMetrics metrics;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();
    private final AttributeValidator attributeValidator = new AttributeValidator();

    public OnecBeforeConvertCallback(MetadataRegistry registry, NumberGenerator numberGenerator,
                                     SecretCipher secretCipher) {
        this(registry, numberGenerator, secretCipher, new OnecMetrics(null));
    }

    public OnecBeforeConvertCallback(MetadataRegistry registry, NumberGenerator numberGenerator,
                                     SecretCipher secretCipher, OnecMetrics metrics) {
        this.registry = registry;
        this.numberGenerator = numberGenerator;
        this.secretCipher = secretCipher;
        this.metrics = metrics;
    }

    @Override
    public Object onBeforeConvert(Object aggregate) {
        return time(operationName("before-convert", aggregate), aggregate, () -> beforeConvert(aggregate));
    }

    private Object beforeConvert(Object aggregate) {
        // Generate UUID for new entities
        if (aggregate instanceof CatalogObject catalog) {
            if (catalog.getId() == null) {
                catalog.setId(UUID.randomUUID());
            }
            if (catalog.isNew()) {
                if (aggregate instanceof OnFillingHandler handler) {
                    handler.onFilling();
                }
                CatalogDescriptor desc = registry.getCatalogDescriptor(catalog.getClass());
                if (desc.autoNumber() && (catalog.getCode() == null || catalog.getCode().isEmpty())) {
                    catalog.setCode(numberGenerator.nextCode(
                            desc.tableName(), desc.codePrefix(), desc.codeLength()));
                }
            }
        } else if (aggregate instanceof DocumentObject document) {
            if (document.getId() == null) {
                document.setId(UUID.randomUUID());
            }
            DocumentDescriptor desc = registry.getDocumentDescriptor(document.getClass());
            if (document.isNew()) {
                if (aggregate instanceof OnFillingHandler handler) {
                    handler.onFilling();
                }
                if (desc.autoNumber() && (document.getNumber() == null || document.getNumber().isEmpty())) {
                    document.setNumber(numberGenerator.nextNumber(
                            desc.tableName(), desc.numberPrefix(), desc.numberLength()));
                }
            }
            // Tabular-section rows are aggregate children with a non-generated UUID @Id; Spring
            // Data JDBC won't assign one, so populate any missing ids before the insert.
            assignTabularRowIds(document, desc);
        } else if (aggregate instanceof AccumulationRecord record) {
            if (record.getId() == null) {
                record.setId(UUID.randomUUID());
            }
        }

        // Call BeforeWriteHandler
        if (aggregate instanceof BeforeWriteHandler handler) {
            time(operationName("before-write", aggregate), aggregate, handler::beforeWrite);
        }

        // Validate: declarative attribute constraints (required, length, min/max, pattern, email)
        // and custom business rules, collected together so the user sees every problem at once.
        time(operationName("validate", aggregate), aggregate, () -> {
            ValidationErrors errors = new ValidationErrors();
            List<AttributeDescriptor> attributes = attributesOf(aggregate);
            if (attributes != null) {
                attributeValidator.validate(aggregate, attributes, errors);
            }
            businessRuleValidator.collect(aggregate, errors);
            errors.throwIfAny();
        });

        // Encrypt secret attributes so the row written holds ciphertext. The plaintext is
        // restored on the in-memory instance by OnecAfterSaveCallback once the write lands.
        time(operationName("encrypt-secrets", aggregate), aggregate, () ->
                SecretFields.apply(aggregate, registry, secretCipher::encrypt));

        return aggregate;
    }

    @SuppressWarnings("unchecked")
    private void assignTabularRowIds(DocumentObject document, DocumentDescriptor desc) {
        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            try {
                Field field = findField(document.getClass(), ts.fieldName());
                field.setAccessible(true);
                Object value = field.get(document);
                if (!(value instanceof List<?> rows)) {
                    continue;
                }
                for (TabularSectionRow row : (List<TabularSectionRow>) rows) {
                    if (row != null && row.getId() == null) {
                        row.setId(UUID.randomUUID());
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(
                        "Failed to assign ids for tabular section '" + ts.name() + "'", e);
            }
        }
    }

    /** The validatable attributes for a catalog/document aggregate, or null for anything else. */
    private List<AttributeDescriptor> attributesOf(Object aggregate) {
        if (aggregate instanceof CatalogObject) {
            return registry.getCatalogDescriptor(aggregate.getClass()).attributes();
        }
        if (aggregate instanceof DocumentObject) {
            return registry.getDocumentDescriptor(aggregate.getClass()).attributes();
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static String operationName(String phase, Object aggregate) {
        if (aggregate instanceof DocumentObject) {
            return "onec.document.save." + phase;
        }
        if (aggregate instanceof CatalogObject) {
            return "onec.catalog.save." + phase;
        }
        if (aggregate instanceof AccumulationRecord) {
            return "onec.register.save." + phase;
        }
        return "onec.persistence." + phase;
    }

    private <T> T time(String operation, Object aggregate, java.util.function.Supplier<T> action) {
        long itemCount = itemCount(aggregate);
        return OnecPerformance.record(operation, itemCount, () -> metrics.time(operation, itemCount, action));
    }

    private void time(String operation, Object aggregate, Runnable action) {
        long itemCount = itemCount(aggregate);
        OnecPerformance.record(operation, itemCount, () -> metrics.time(operation, itemCount, action));
    }

    private static long itemCount(Object aggregate) {
        return aggregate instanceof DocumentObject ? 1 : 0;
    }
}
