package com.onec.spring;

import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.lifecycle.OnFillingHandler;
import com.onec.metadata.*;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.model.TabularSectionRow;
import com.onec.numbering.NumberGenerator;
import com.onec.rules.BusinessRuleValidator;
import com.onec.security.SecretCipher;
import com.onec.security.SecretFields;

import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

public class OnecBeforeConvertCallback implements BeforeConvertCallback<Object> {

    private final MetadataRegistry registry;
    private final NumberGenerator numberGenerator;
    private final SecretCipher secretCipher;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();

    public OnecBeforeConvertCallback(MetadataRegistry registry, NumberGenerator numberGenerator,
                                     SecretCipher secretCipher) {
        this.registry = registry;
        this.numberGenerator = numberGenerator;
        this.secretCipher = secretCipher;
    }

    @Override
    public Object onBeforeConvert(Object aggregate) {
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
            handler.beforeWrite();
        }

        // Validate required attributes
        validateRequired(aggregate);
        businessRuleValidator.validate(aggregate);

        // Encrypt secret attributes so the row written holds ciphertext. The plaintext is
        // restored on the in-memory instance by OnecAfterSaveCallback once the write lands.
        SecretFields.apply(aggregate, registry, secretCipher::encrypt);

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

    private void validateRequired(Object aggregate) {
        List<AttributeDescriptor> attributes;
        if (aggregate instanceof CatalogObject) {
            attributes = registry.getCatalogDescriptor(aggregate.getClass()).attributes();
        } else if (aggregate instanceof DocumentObject) {
            attributes = registry.getDocumentDescriptor(aggregate.getClass()).attributes();
        } else {
            return;
        }

        for (AttributeDescriptor attr : attributes) {
            if (!attr.required()) continue;
            try {
                Field field = findField(aggregate.getClass(), attr.fieldName());
                field.setAccessible(true);
                Object value = field.get(aggregate);
                if (value == null) {
                    throw new IllegalStateException(
                            "Required attribute '" + attr.fieldName() + "' is null on " +
                                    aggregate.getClass().getSimpleName());
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to validate required attribute: " + attr.fieldName(), e);
            }
        }
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
}
