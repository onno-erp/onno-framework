package su.onno.spring;

import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.messaging.OutboxWriter;
import su.onno.metadata.MetadataRegistry;
import su.onno.events.EntityChangePublisher;

import org.springframework.data.relational.core.mapping.event.BeforeDeleteCallback;
import org.springframework.data.relational.core.conversion.MutableAggregateChange;

public class OnnoBeforeDeleteCallback implements BeforeDeleteCallback<Object> {

    public OnnoBeforeDeleteCallback() {
    }

    public OnnoBeforeDeleteCallback(OutboxWriter outboxWriter) {
    }

    public OnnoBeforeDeleteCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                    EntityChangePublisher entityChangePublisher) {
    }

    @Override
    public Object onBeforeDelete(Object aggregate, MutableAggregateChange<Object> aggregateChange) {
        if (aggregate instanceof BeforeDeleteHandler handler) {
            handler.beforeDelete();
        }
        return aggregate;
    }
}
