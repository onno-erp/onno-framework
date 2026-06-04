package com.onec.spring;

import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.TabularSectionDescriptor;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.model.TabularSectionRow;
import com.onec.security.SecretCipher;
import com.onec.security.SecretFields;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Marks freshly-loaded aggregates as not-new so that a subsequent {@code repository.save(...)}
 * issues an UPDATE rather than an INSERT.
 *
 * <p>{@link CatalogObject}/{@link DocumentObject}/{@link AccumulationRecord} all implement
 * {@link org.springframework.data.domain.Persistable} with {@code isNew} defaulting to {@code true}
 * (so a brand-new instance inserts). Spring Data JDBC never flips that flag back after reading a row,
 * so without this callback an entity loaded from the database would still report {@code isNew == true}
 * and {@code save()} would attempt to re-INSERT it — causing a duplicate-key error, or a silent no-op
 * when the INSERT is ignored. Running on {@code AfterConvert} (after the row is mapped to an object,
 * on every load path) is the canonical place to reset it.
 */
public class OnecAfterConvertCallback implements AfterConvertCallback<Object> {

    private final MetadataRegistry registry;
    private final SecretCipher secretCipher;

    public OnecAfterConvertCallback(MetadataRegistry registry, SecretCipher secretCipher) {
        this.registry = registry;
        this.secretCipher = secretCipher;
    }

    @Override
    public Object onAfterConvert(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            catalog.setNew(false);
        } else if (aggregate instanceof DocumentObject document) {
            document.setNew(false);
            // lineNumber is @Transient (the list index owns the _line_number key column), so
            // restore each row's 1-based position after a Spring Data JDBC load.
            restoreLineNumbers(document);
        } else if (aggregate instanceof AccumulationRecord record) {
            record.setNew(false);
        }
        // Decrypt secret attributes so repository-loaded entities expose plaintext to
        // application code. The database row holds ciphertext; decrypt is a no-op on
        // legacy plaintext (values without the cipher prefix).
        SecretFields.apply(aggregate, registry, secretCipher::decrypt);
        return aggregate;
    }

    @SuppressWarnings("unchecked")
    private void restoreLineNumbers(DocumentObject document) {
        DocumentDescriptor desc = registry.getDocumentDescriptor(document.getClass());
        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            try {
                Field field = findField(document.getClass(), ts.fieldName());
                field.setAccessible(true);
                Object value = field.get(document);
                if (!(value instanceof List<?> rows)) {
                    continue;
                }
                int line = 1;
                for (TabularSectionRow row : (List<TabularSectionRow>) rows) {
                    if (row != null) {
                        row.setLineNumber(line);
                    }
                    line++;
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(
                        "Failed to restore line numbers for tabular section '" + ts.name() + "'", e);
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
