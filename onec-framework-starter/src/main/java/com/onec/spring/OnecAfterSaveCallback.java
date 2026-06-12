package com.onec.spring;

import com.onec.annotations.DomainEvent;
import com.onec.annotations.EventTiming;
import com.onec.events.EntityChangePublisher;
import com.onec.events.EntityChangedEvent;
import com.onec.lifecycle.AfterWriteHandler;
import com.onec.messaging.OutboxWriter;
import com.onec.metadata.MetadataRegistry;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.performance.OnecPerformance;
import com.onec.security.SecretCipher;
import com.onec.security.SecretFields;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;

public class OnecAfterSaveCallback implements AfterSaveCallback<Object>, AfterConvertCallback<Object> {

    private final OutboxWriter outboxWriter;
    private final MetadataRegistry registry;
    private final SecretCipher secretCipher;
    private final EntityChangePublisher entityChangePublisher;
    private final OnecMetrics metrics;

    public OnecAfterSaveCallback() {
        this(null, null, null, null);
    }

    public OnecAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher) {
        this(outboxWriter, registry, secretCipher, null);
    }

    public OnecAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher,
                                 EntityChangePublisher entityChangePublisher) {
        this(outboxWriter, registry, secretCipher, entityChangePublisher, new OnecMetrics(null));
    }

    public OnecAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher,
                                 EntityChangePublisher entityChangePublisher, OnecMetrics metrics) {
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

        // OnecBeforeConvertCallback encrypted the secret fields in place before the row was
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
