package com.onec.spring;

import com.onec.annotations.DomainEvent;
import com.onec.annotations.EventTiming;
import com.onec.events.EntityChangePublisher;
import com.onec.events.EntityChangedEvent;
import com.onec.lifecycle.BeforeDeleteHandler;
import com.onec.messaging.OutboxWriter;
import com.onec.metadata.MetadataRegistry;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.BeforeDeleteCallback;
import org.springframework.data.relational.core.conversion.MutableAggregateChange;

public class OnecBeforeDeleteCallback implements BeforeDeleteCallback<Object> {

    private final OutboxWriter outboxWriter;
    private final MetadataRegistry registry;
    private final EntityChangePublisher entityChangePublisher;

    public OnecBeforeDeleteCallback() {
        this(null, null, null);
    }

    public OnecBeforeDeleteCallback(OutboxWriter outboxWriter) {
        this(outboxWriter, null, null);
    }

    public OnecBeforeDeleteCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                    EntityChangePublisher entityChangePublisher) {
        this.outboxWriter = outboxWriter;
        this.registry = registry;
        this.entityChangePublisher = entityChangePublisher;
    }

    @Override
    public Object onBeforeDelete(Object aggregate, MutableAggregateChange<Object> aggregateChange) {
        if (aggregate instanceof BeforeDeleteHandler handler) {
            handler.beforeDelete();
        }
        publishDomainEvents(aggregate, EventTiming.AFTER_DELETE);
        publishEntityChange(aggregate);
        return aggregate;
    }

    /**
     * Emits an {@link EntityChangedEvent} for a {@code repository.delete}, mirroring the generic
     * controllers' {@code deleted} event so both write paths are observable (issues #28, #29).
     */
    private void publishEntityChange(Object aggregate) {
        if (entityChangePublisher == null || registry == null) return;
        if (aggregate instanceof CatalogObject catalog) {
            String name = registry.getCatalogDescriptor(catalog.getClass()).logicalName();
            entityChangePublisher.publish(new EntityChangedEvent(
                    EntityChangedEvent.DELETED, EntityChangedEvent.CATALOG, name,
                    catalog.getId(), catalog.getCode()));
        } else if (aggregate instanceof DocumentObject document) {
            String name = registry.getDocumentDescriptor(document.getClass()).logicalName();
            entityChangePublisher.publish(new EntityChangedEvent(
                    EntityChangedEvent.DELETED, EntityChangedEvent.DOCUMENT, name,
                    document.getId(), document.getNumber()));
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
}
