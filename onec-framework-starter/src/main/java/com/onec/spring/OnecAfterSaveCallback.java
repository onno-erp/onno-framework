package com.onec.spring;

import com.onec.annotations.DomainEvent;
import com.onec.annotations.EventTiming;
import com.onec.lifecycle.AfterWriteHandler;
import com.onec.messaging.OutboxWriter;
import com.onec.metadata.MetadataRegistry;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.security.SecretCipher;
import com.onec.security.SecretFields;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;

public class OnecAfterSaveCallback implements AfterSaveCallback<Object>, AfterConvertCallback<Object> {

    private final OutboxWriter outboxWriter;
    private final MetadataRegistry registry;
    private final SecretCipher secretCipher;

    public OnecAfterSaveCallback() {
        this(null, null, null);
    }

    public OnecAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry, SecretCipher secretCipher) {
        this.outboxWriter = outboxWriter;
        this.registry = registry;
        this.secretCipher = secretCipher;
    }

    @Override
    public Object onAfterSave(Object aggregate) {
        markNotNew(aggregate);

        // OnecBeforeConvertCallback encrypted the secret fields in place before the row was
        // written; decrypt them back so the instance the caller holds keeps its plaintext.
        if (registry != null && secretCipher != null) {
            SecretFields.apply(aggregate, registry, secretCipher::decrypt);
        }

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
