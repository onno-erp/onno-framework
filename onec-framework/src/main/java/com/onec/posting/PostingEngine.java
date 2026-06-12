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
import com.onec.performance.OnecPerformance;
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

/**
 * Posts documents: runs their {@link Postable} logic, writes the resulting register movements and
 * totals, enforces non-negative balances, then flips {@code _posted}.
 *
 * <h2>Transaction boundary — important</h2>
 * Posting runs inside its <em>own</em> JDBI transaction ({@link Jdbi#useTransaction}) on a connection
 * obtained directly from the {@code DataSource}. It is <strong>not</strong> enlisted in any ambient
 * Spring {@code @Transactional} that the caller may have opened. Two consequences follow:
 * <ul>
 *   <li>Do <strong>not</strong> wrap "save the document, then post it" in a single
 *       {@code @Transactional} method. The {@code save()} row is not yet committed, so JDBI — on a
 *       separate connection — cannot see it, the {@code UPDATE ... SET _posted = TRUE} matches zero
 *       rows, and you silently get register movements with {@code _posted} still {@code false}.
 *       Save (and let it commit) first, then call {@link PostingService}/{@code post(...)}.</li>
 *   <li>Posting is atomic in itself (movements, totals, balance checks and the {@code _posted} flag
 *       all commit or roll back together), but it is a distinct transaction from the document write.</li>
 * </ul>
 */
public class PostingEngine {

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private final Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap;
    private final BusinessRuleValidator businessRuleValidator = new BusinessRuleValidator();
    private final OutboxWriter outboxWriter;
    private final PostEventPublisher eventPublisher;

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap) {
        this(jdbi, registry, repositoryMap, null);
    }

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap,
                         OutboxWriter outboxWriter) {
        this(jdbi, registry, repositoryMap, outboxWriter, null);
    }

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap,
                         OutboxWriter outboxWriter,
                         PostEventPublisher eventPublisher) {
        this.jdbi = jdbi;
        this.registry = registry;
        this.repositoryMap = repositoryMap;
        this.outboxWriter = outboxWriter;
        this.eventPublisher = eventPublisher;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void post(DocumentObject document) {
        OnecPerformance.record("onec.document.post", 1, () -> doPost(document));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doPost(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }

        if (document instanceof BeforeWriteHandler writer) {
            OnecPerformance.record("onec.document.before-write", 1, writer::beforeWrite);
        }

        if (document instanceof BeforePostHandler handler) {
            OnecPerformance.record("onec.document.before-post", 1, handler::beforePost);
        }
        OnecPerformance.record("onec.document.validate", 1, () -> businessRuleValidator.validate(document));

        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        PostingContext context = buildPostingContext(document);

        OnecPerformance.record("onec.document.post.transaction", 1, () -> jdbi.useTransaction(handle -> {
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
        }));

        document.setPosted(true);
        publishDomainEvents(document, EventTiming.AFTER_POST);

        if (document instanceof AfterPostHandler handler) {
            OnecPerformance.record("onec.document.after-post", 1, handler::afterPost);
        }

        publishApplicationEvent(new DocumentPostedEvent(document));
    }

    public PostingPreview preview(DocumentObject document) {
        return OnecPerformance.record("onec.document.post.preview", 1, () -> doPreview(document));
    }

    private PostingPreview doPreview(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }
        if (document instanceof BeforeWriteHandler writer) {
            OnecPerformance.record("onec.document.before-write", 1, writer::beforeWrite);
        }
        if (document instanceof BeforePostHandler handler) {
            OnecPerformance.record("onec.document.before-post", 1, handler::beforePost);
        }
        OnecPerformance.record("onec.document.validate", 1, () -> businessRuleValidator.validate(document));

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
        OnecPerformance.record("onec.document.unpost", 1, () -> doUnpost(document));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doUnpost(DocumentObject document) {
        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        OnecPerformance.record("onec.document.unpost.transaction", 1, () -> jdbi.useTransaction(handle -> {
            for (RegisterRepositoryImpl<?> repo : repositoryMap.values()) {
                RegisterPersistence persistence = repo.getPersistence();
                persistence.reverseTotals(handle, document.getId());
                persistence.deactivateRecords(handle, document.getId());
            }

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = FALSE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        }));

        document.setPosted(false);

        publishApplicationEvent(new DocumentUnpostedEvent(document));
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
            OnecPerformance.record("onec.document.handle-posting", 1, () -> postable.handlePosting(context));
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

    private void publishApplicationEvent(Object event) {
        if (eventPublisher != null) {
            eventPublisher.publish(event);
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
