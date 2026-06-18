package su.onno.spring;

import su.onno.annotations.DomainEvent;
import su.onno.annotations.EventTiming;
import su.onno.events.EntityChangePublisher;
import su.onno.events.EntityChangedEvent;
import su.onno.lifecycle.AfterWriteHandler;
import su.onno.messaging.OutboxWriter;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.AccumulationRecord;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.performance.OnnoPerformance;
import su.onno.security.SecretCipher;
import su.onno.security.SecretFields;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;

public class OnnoAfterSaveCallback implements AfterSaveCallback<Object>, AfterConvertCallback<Object> {

    private final OutboxWriter outboxWriter;
    private final MetadataRegistry registry;
    private final SecretCipher secretCipher;
    private final EntityChangePublisher entityChangePublisher;
    private final OnnoMetrics metrics;

    public OnnoAfterSaveCallback() {
        this(null, null, null, null);
    }

    public OnnoAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher) {
        this(outboxWriter, registry, secretCipher, null);
    }

    public OnnoAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher,
                                 EntityChangePublisher entityChangePublisher) {
        this(outboxWriter, registry, secretCipher, entityChangePublisher, new OnnoMetrics(null));
    }

    public OnnoAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher,
                                 EntityChangePublisher entityChangePublisher, OnnoMetrics metrics) {
        this.outboxWriter = outboxWriter;
        this.registry = registry;
        this.secretCipher = secretCipher;
        this.entityChangePublisher = entityChangePublisher;
        this.metrics = metrics;
    }

    @Override
    public Object onAfterSave(Object aggregate) {
        return time(operationName("after-save", aggregate), aggregate, () -> afterSave(aggregate));
    }

    private Object afterSave(Object aggregate) {
        // Read isNew before markNotNew flips it: a still-new aggregate at this point was inserted.
        boolean wasNew = isNew(aggregate);
        markNotNew(aggregate);

        // OnnoBeforeConvertCallback encrypted the secret fields in place before the row was
        // written; decrypt them back so the instance the caller holds keeps its plaintext.
        if (registry != null && secretCipher != null) {
            time(operationName("decrypt-secrets", aggregate), aggregate, () ->
                    SecretFields.apply(aggregate, registry, secretCipher::decrypt));
        }

        // Call AfterWriteHandler
        if (aggregate instanceof AfterWriteHandler handler) {
            time(operationName("after-write", aggregate), aggregate, handler::afterWrite);
        }
        publishDomainEvents(aggregate, EventTiming.AFTER_WRITE);
        publishEntityChange(aggregate, wasNew ? EntityChangedEvent.CREATED : EntityChangedEvent.UPDATED);

        return aggregate;
    }

    @Override
    public Object onAfterConvert(Object aggregate) {
        return time(operationName("after-convert", aggregate), aggregate, () -> {
            markNotNew(aggregate);
            return aggregate;
        });
    }

    private boolean isNew(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            return catalog.isNew();
        } else if (aggregate instanceof DocumentObject document) {
            return document.isNew();
        } else if (aggregate instanceof AccumulationRecord record) {
            return record.isNew();
        }
        return false;
    }

    /**
     * Emits an {@link EntityChangedEvent} for catalog/document saves so a {@code repository.save}
     * is observable by the same listeners the generic controllers feed (issues #28, #29). The event
     * carries the natural key (catalog code / document number) so listeners can target a resource.
     */
    private void publishEntityChange(Object aggregate, String changeType) {
        if (entityChangePublisher == null || registry == null) return;
        if (aggregate instanceof CatalogObject catalog) {
            String name = registry.getCatalogDescriptor(catalog.getClass()).logicalName();
            entityChangePublisher.publish(new EntityChangedEvent(
                    changeType, EntityChangedEvent.CATALOG, name, catalog.getId(), catalog.getCode()));
        } else if (aggregate instanceof DocumentObject document) {
            String name = registry.getDocumentDescriptor(document.getClass()).logicalName();
            entityChangePublisher.publish(new EntityChangedEvent(
                    changeType, EntityChangedEvent.DOCUMENT, name, document.getId(), document.getNumber()));
        }
    }

    private void markNotNew(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            catalog.setNew(false);
        } else if (aggregate instanceof DocumentObject document) {
            document.setNew(false);
        } else if (aggregate instanceof AccumulationRecord record) {
            record.setNew(false);
        }
    }

    private void publishDomainEvents(Object aggregate, EventTiming timing) {
        if (outboxWriter == null) return;
        for (DomainEvent event : aggregate.getClass().getAnnotationsByType(DomainEvent.class)) {
            if (event.when() != timing) continue;
            String id = aggregate instanceof CatalogObject catalog && catalog.getId() != null
                    ? catalog.getId().toString()
                    : aggregate instanceof DocumentObject document && document.getId() != null
                    ? document.getId().toString()
                    : null;
            String payload = "{\"aggregateType\":\"" + aggregate.getClass().getName() +
                    "\",\"aggregateId\":\"" + id + "\"}";
            outboxWriter.append(aggregate.getClass().getName(), id, event.name(), payload);
        }
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
