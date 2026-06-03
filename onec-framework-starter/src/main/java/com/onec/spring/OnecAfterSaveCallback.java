package com.onec.spring;

import com.onec.annotations.DomainEvent;
import com.onec.annotations.EventTiming;
import com.onec.lifecycle.AfterWriteHandler;
import com.onec.messaging.OutboxWriter;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;

public class OnecAfterSaveCallback implements AfterSaveCallback<Object>, AfterConvertCallback<Object> {

    private final OutboxWriter outboxWriter;

    public OnecAfterSaveCallback() {
        this(null);
    }

    public OnecAfterSaveCallback(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public Object onAfterSave(Object aggregate) {
        markNotNew(aggregate);

        // Call AfterWriteHandler
        if (aggregate instanceof AfterWriteHandler handler) {
            handler.afterWrite();
        }
        publishDomainEvents(aggregate, EventTiming.AFTER_WRITE);

        return aggregate;
    }

    @Override
    public Object onAfterConvert(Object aggregate) {
        markNotNew(aggregate);
        return aggregate;
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
}
