package com.onec.posting;

import com.onec.lifecycle.AfterPostHandler;
import com.onec.lifecycle.BeforePostHandler;
import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.lifecycle.Postable;
import com.onec.annotations.DomainEvent;
import com.onec.annotations.EventTiming;
import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.TabularSectionDescriptor;
import com.onec.messaging.OutboxWriter;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;
import com.onec.model.DocumentObject;
import com.onec.model.TabularSectionRow;
import com.onec.repository.RegisterRepositoryImpl;
import com.onec.rules.BusinessRuleValidator;
import com.onec.types.Ref;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostingEngine {

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private final Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();
    private final OutboxWriter outboxWriter;

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap) {
        this(jdbi, registry, repositoryMap, null);
    }

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap,
                         OutboxWriter outboxWriter) {
        this.jdbi = jdbi;
        this.registry = registry;
        this.repositoryMap = repositoryMap;
        this.outboxWriter = outboxWriter;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void post(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }

        if (document instanceof BeforeWriteHandler writer) {
            writer.beforeWrite();
        }

        if (document instanceof BeforePostHandler handler) {
            handler.beforePost();
        }
        businessRuleValidator.validate(document);

        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        PostingContext context = buildPostingContext(document);

        jdbi.useTransaction(handle -> {
            for (RegisterRepositoryImpl<?> repo : context.touchedRepositories()) {
                RegisterPersistence persistence = repo.getPersistence();
                persistence.insertRecords(handle, repo.getPendingMovements(),
                        document.getId(), document.getDate());
                persistence.updateTotals(handle, repo.getPendingMovements());
                repo.clearPending();
            }

            // Check that no BALANCE register went negative
            for (RegisterRepositoryImpl<?> repo : context.touchedRepositories()) {
                checkNonNegativeBalances(handle, repo.getPersistence().getDescriptor());
            }

            // Write back computed fields from beforeWrite()
            writeBackDocument(handle, docDescriptor, document);

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = TRUE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        });

        document.setPosted(true);
        publishDomainEvents(document, EventTiming.AFTER_POST);

        if (document instanceof AfterPostHandler handler) {
            handler.afterPost();
        }
    }

    public PostingPreview preview(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }
        if (document instanceof BeforeWriteHandler writer) {
            writer.beforeWrite();
        }
        if (document instanceof BeforePostHandler handler) {
            handler.beforePost();
        }
        businessRuleValidator.validate(document);

        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());
        PostingContext context = buildPostingContext(document);

        List<PostingPreview.RegisterPreview> registers = context.touchedRepositories().stream()
                .map(repo -> {
                    AccumulationRegisterDescriptor desc = repo.getPersistence().getDescriptor();
                    @SuppressWarnings("unchecked")
                    List<AccumulationRecord> movements = (List<AccumulationRecord>) (List<?>) repo.getPendingMovements();
                    List<Map<String, Object>> rows = movements.stream()
                            .map(record -> movementMap(desc, record))
                            .toList();
                    return new PostingPreview.RegisterPreview(
                            desc.logicalName(),
                            desc.tableName(),
                            desc.accumulationType().name(),
                            rows);
                })
                .toList();

        clearPending(context);
        return new PostingPreview(
                docDescriptor.logicalName(),
                document.getId() == null ? null : document.getId().toString(),
                registers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unpost(DocumentObject document) {
        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        jdbi.useTransaction(handle -> {
            for (RegisterRepositoryImpl<?> repo : repositoryMap.values()) {
                RegisterPersistence persistence = repo.getPersistence();
                persistence.reverseTotals(handle, document.getId());
                persistence.deactivateRecords(handle, document.getId());
            }

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = FALSE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        });

        document.setPosted(false);
    }

    private void checkNonNegativeBalances(Handle handle,
                                           AccumulationRegisterDescriptor desc) {
        if (desc.accumulationType() != AccumulationType.BALANCE) return;

        for (AttributeDescriptor res : desc.resources()) {
            String sql = "SELECT COUNT(*) FROM " + desc.totalsTableName() +
                    " WHERE " + res.columnName() + " < 0";
            int count = handle.createQuery(sql).mapTo(Integer.class).one();
            if (count > 0) {
                throw new IllegalStateException(
                        "Insufficient " + res.displayName().toLowerCase() +
                        " in register \"" + desc.logicalName() + "\". " +
                        "Posting would result in negative balance.");
            }
        }
    }

    private PostingContext buildPostingContext(DocumentObject document) {
        PostingContext context = new PostingContext(repositoryMap);
        if (document instanceof Postable postable) {
            postable.handlePosting(context);
        }
        return context;
    }

    private Map<String, Object> movementMap(AccumulationRegisterDescriptor desc, AccumulationRecord record) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("movementType", record.getMovementType().name());
        for (AttributeDescriptor dim : desc.dimensions()) {
            map.put(dim.fieldName(), convertForDb(getFieldValue(record, dim.fieldName())));
        }
        for (AttributeDescriptor res : desc.resources()) {
            map.put(res.fieldName(), convertForDb(getFieldValue(record, res.fieldName())));
        }
        return map;
    }

    private void clearPending(PostingContext context) {
        for (RegisterRepositoryImpl<?> repo : context.touchedRepositories()) {
            repo.clearPending();
        }
    }

    private void publishDomainEvents(DocumentObject document, EventTiming timing) {
        if (outboxWriter == null) return;
        for (DomainEvent event : document.getClass().getAnnotationsByType(DomainEvent.class)) {
            if (event.when() != timing) continue;
            String payload = "{\"documentType\":\"" + document.getClass().getName() +
                    "\",\"documentId\":\"" + document.getId() + "\"}";
            outboxWriter.append(document.getClass().getName(),
                    document.getId() == null ? null : document.getId().toString(),
                    event.name(),
                    payload);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeBackDocument(Handle handle, DocumentDescriptor desc, DocumentObject document) {
        // Update document-level attributes
        List<AttributeDescriptor> attrs = desc.attributes();
        if (!attrs.isEmpty()) {
            String setClauses = attrs.stream()
                    .map(a -> a.columnName() + " = :" + a.columnName())
                    .collect(Collectors.joining(", "));

            var update = handle.createUpdate(
                    "UPDATE " + desc.tableName() + " SET " + setClauses + " WHERE _id = :_id")
                    .bind("_id", document.getId());

            for (AttributeDescriptor attr : attrs) {
                Object val = getFieldValue(document, attr.fieldName());
                update.bind(attr.columnName(), convertForDb(val));
            }
            update.execute();
        }

        // Update tabular section rows
        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            List<?> rows = getListField(document, ts.fieldName());
            if (rows == null) continue;

            for (Object rowObj : rows) {
                if (!(rowObj instanceof TabularSectionRow row)) continue;
                if (row.getId() == null) continue;

                List<AttributeDescriptor> rowAttrs = ts.attributes();
                if (rowAttrs.isEmpty()) continue;

                String rowSetClauses = rowAttrs.stream()
                        .map(a -> a.columnName() + " = :" + a.columnName())
                        .collect(Collectors.joining(", "));

                var rowUpdate = handle.createUpdate(
                        "UPDATE " + ts.tableName() + " SET " + rowSetClauses + " WHERE _id = :_id")
                        .bind("_id", row.getId());

                for (AttributeDescriptor attr : rowAttrs) {
                    Object val = getFieldValue(rowObj, attr.fieldName());
                    rowUpdate.bind(attr.columnName(), convertForDb(val));
                }
                rowUpdate.execute();
            }
        }
    }

    private Object convertForDb(Object val) {
        if (val == null) return null;
        if (val instanceof Ref<?> ref) return ref.id();
        if (val instanceof Enum<?> e) {
            var enumDesc = registry.allEnumerations().stream()
                    .filter(ed -> ed.javaClass().equals(e.getClass()))
                    .findFirst().orElse(null);
            if (enumDesc != null) {
                return enumDesc.values().stream()
                        .filter(v -> v.name().equals(e.name()))
                        .findFirst()
                        .map(v -> (Object) v.id())
                        .orElse(null);
            }
        }
        return val;
    }

    private Object getFieldValue(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> getListField(Object target, String fieldName) {
        Object val = getFieldValue(target, fieldName);
        return val instanceof List<?> list ? list : null;
    }

    private Field findField(Class<?> clazz, String name) {
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
