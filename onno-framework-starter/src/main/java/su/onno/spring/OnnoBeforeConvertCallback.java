package su.onno.spring;

import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.metadata.*;
import su.onno.model.AccumulationRecord;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.numbering.NumberGenerator;
import su.onno.performance.OnnoPerformance;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodOperation;
import su.onno.rules.BusinessRuleValidator;
import su.onno.security.SecretCipher;
import su.onno.security.SecretFields;
import su.onno.validation.AttributeValidator;
import su.onno.validation.BeanValidation;
import su.onno.validation.ValidationErrors;

import org.jdbi.v3.core.Jdbi;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

public class OnnoBeforeConvertCallback implements BeforeConvertCallback<Object> {

    private final MetadataRegistry registry;
    private final NumberGenerator numberGenerator;
    private final SecretCipher secretCipher;
    private final OnnoMetrics metrics;
    private PeriodCloseService periodClose;
    private Jdbi jdbi;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();
    private final AttributeValidator attributeValidator = new AttributeValidator();

    public OnnoBeforeConvertCallback(MetadataRegistry registry, NumberGenerator numberGenerator,
                                     SecretCipher secretCipher) {
        this(registry, numberGenerator, secretCipher, new OnnoMetrics(null));
    }

    public OnnoBeforeConvertCallback(MetadataRegistry registry, NumberGenerator numberGenerator,
                                     SecretCipher secretCipher, OnnoMetrics metrics) {
        this.registry = registry;
        this.numberGenerator = numberGenerator;
        this.secretCipher = secretCipher;
        this.metrics = metrics;
    }

    /** Enables period-close enforcement on document saves (including the soft delete). */
    public void setPeriodCloseService(PeriodCloseService periodClose) {
        this.periodClose = periodClose;
    }

    /**
     * Enables the posted re-check of a soft delete: the stored row is locked and read on the save's
     * transaction, so a document posted after it was loaded is not marked deleted with stale state.
     */
    public void setJdbi(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    @org.springframework.lang.NonNull
    public Object onBeforeConvert(@org.springframework.lang.NonNull Object aggregate) {
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
            assignTabularRowIds(catalog, registry.getCatalogDescriptor(catalog.getClass()).tabularSections());
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
            assignTabularRowIds(document, desc.tabularSections());
            if (periodClose != null) {
                // The stored date and the date being written must both be outside closed periods.
                periodClose.requireDocumentOpen(desc, document.getId(), document.getDate(),
                        periodClose.organizationOf(document),
                        document.isNew() ? PeriodOperation.CREATE
                                : document.isDeletionMark() ? PeriodOperation.DELETE : PeriodOperation.UPDATE);
            }
        } else if (aggregate instanceof AccumulationRecord record) {
            if (record.getId() == null) {
                record.setId(UUID.randomUUID());
            }
        }

        // A soft delete (EntityDeleteService) is its own lifecycle: BeforeDeleteHandler already ran,
        // and neither recalculation hooks nor current-state validation apply to a record on its way out.
        boolean deleting = DeleteWriteContext.isDeleting(aggregate);
        if (deleting && aggregate instanceof DocumentObject document) {
            requireUnpostedForDelete(document);
        }

        if (!deleting && aggregate instanceof BeforeWriteHandler handler) {
            time(operationName("before-write", aggregate), aggregate, handler::beforeWrite);
        }

        // Validate: attribute constraints (column length plus rules a Bean Validation provider does
        // not own), Jakarta Bean Validation on the entity and its rows, and the business rules,
        // collected together so the user sees every problem at once. A soft delete skips it.
        if (!deleting) {
            time(operationName("validate", aggregate), aggregate, () -> {
                ValidationErrors errors = new ValidationErrors();
                List<AttributeDescriptor> attributes = attributesOf(aggregate);
                if (attributes != null) {
                    attributeValidator.validate(aggregate, attributes, errors);
                    BeanValidation.validate(aggregate, errors);
                }
                businessRuleValidator.collect(aggregate, errors);
                errors.throwIfAny();
            });
        }

        // Encrypt secret attributes so the row written holds ciphertext. The plaintext is
        // restored on the in-memory instance by OnnoAfterSaveCallback once the write lands.
        time(operationName("encrypt-secrets", aggregate), aggregate, () ->
                SecretFields.apply(aggregate, registry, secretCipher::encrypt));

        return aggregate;
    }

    /**
     * Posting flips {@code _posted} without bumping the version, so the optimistic lock alone would
     * not notice a post that landed after the document was loaded. Lock the row on the save's
     * transaction (JDBI joins it) and re-read the flag; a concurrent post then waits for this delete
     * and finds the document deletion-marked.
     */
    private void requireUnpostedForDelete(DocumentObject document) {
        if (jdbi == null) return;
        DocumentDescriptor desc = registry.getDocumentDescriptor(document.getClass());
        boolean posted = jdbi.withHandle(h -> h.createQuery(
                        "SELECT _posted FROM " + desc.tableName() + " WHERE _id = :id FOR UPDATE")
                .bind("id", document.getId())
                .mapTo(Boolean.class)
                .findOne()
                .orElse(false));
        if (posted) throw EntityDeleteService.postedDocument(document);
    }

    @SuppressWarnings("unchecked")
    private void assignTabularRowIds(Object document, List<TabularSectionDescriptor> sections) {
        for (TabularSectionDescriptor ts : sections) {
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
            return "onno.document.save." + phase;
        }
        if (aggregate instanceof CatalogObject) {
            return "onno.catalog.save." + phase;
        }
        if (aggregate instanceof AccumulationRecord) {
            return "onno.register.save." + phase;
        }
        return "onno.persistence." + phase;
    }

    private <T> T time(String operation, Object aggregate, java.util.function.Supplier<T> action) {
        long itemCount = itemCount(aggregate);
        return OnnoPerformance.record(operation, itemCount, () -> metrics.time(operation, itemCount, action));
    }

    private void time(String operation, Object aggregate, Runnable action) {
        long itemCount = itemCount(aggregate);
        OnnoPerformance.record(operation, itemCount, () -> metrics.time(operation, itemCount, action));
    }

    private static long itemCount(Object aggregate) {
        return aggregate instanceof DocumentObject ? 1 : 0;
    }
}
