package su.onno.crm.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import su.onno.events.EntityChangedEvent;
import su.onno.ui.UiEventPublisher;

/** Content-free invalidation; scoped endpoints recheck the subscriber's permissions. */
@Component
public class CrmWorkspaceEvents {
    private final UiEventPublisher events;
    private final CrmCustomerBinding<?> customers;
    public CrmWorkspaceEvents(UiEventPublisher events,CrmCustomerBinding<?> customers){this.events=events;this.customers=customers;}
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void changed(EntityChangedEvent event) {
        String name=event.entityName().replace("_","").toLowerCase(java.util.Locale.ROOT);
        if(java.util.Set.of("crmconversations","crmconversationmessages").contains(name) || name.equals(customers.catalog().name().replace("_", "").toLowerCase(java.util.Locale.ROOT)))
            events.publish(EntityChangedEvent.UPDATED,"page","crm-inbox-workspaces",null);
    }
}
