package com.onec.spring;

import com.onec.annotations.DomainEvent;
import com.onec.annotations.EventTiming;
import com.onec.lifecycle.BeforeDeleteHandler;
import com.onec.messaging.OutboxWriter;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.BeforeDeleteCallback;
import org.springframework.data.relational.core.conversion.MutableAggregateChange;

public class OneCBeforeDeleteCallback implements BeforeDeleteCallback<Object> {

    private final OutboxWriter outboxWriter;

    public OneCBeforeDeleteCallback() {
        this(null);
    }

    public OneCBeforeDeleteCallback(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public Object onBeforeDelete(Object aggregate, MutableAggregateChange<Object> aggregateChange) {
        if (aggregate instanceof BeforeDeleteHandler handler) {
            handler.beforeDelete();
        }
        publishDomainEvents(aggregate, EventTiming.AFTER_DELETE);
        return aggregate;
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
