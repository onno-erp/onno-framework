package su.onno.spring;

import su.onno.annotations.DomainEvent;
import su.onno.annotations.EventTiming;
import su.onno.events.EntityChangePublisher;
import su.onno.events.EntityChangedEvent;
import su.onno.messaging.OutboxWriter;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.AfterDeleteCallback;

/** Publishes hard-delete events only after Spring Data has performed the delete. */
public class OnnoAfterDeleteCallback implements AfterDeleteCallback<Object> {

    private final OutboxWriter outboxWriter;
    private final MetadataRegistry registry;
    private final EntityChangePublisher entityChangePublisher;

    public OnnoAfterDeleteCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                   EntityChangePublisher entityChangePublisher) {
        this.outboxWriter = outboxWriter;
        this.registry = registry;
        this.entityChangePublisher = entityChangePublisher;
    }

    @Override
    public Object onAfterDelete(Object aggregate) {
        publishDomainEvents(aggregate);
        publishEntityChange(aggregate);
        return aggregate;
    }

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

    private void publishDomainEvents(Object aggregate) {
        if (outboxWriter == null) return;
        for (DomainEvent event : aggregate.getClass().getAnnotationsByType(DomainEvent.class)) {
            if (event.when() != EventTiming.AFTER_DELETE) continue;
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
