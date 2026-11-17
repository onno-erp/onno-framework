package su.onno.spring;

import su.onno.events.EntityChangePublisher;
import su.onno.events.EntityChangedEvent;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bridges the framework's {@link EntityChangePublisher} SPI onto Spring's
 * {@link ApplicationEventPublisher}. Publication waits for an active Spring transaction to commit;
 * rollback discards the event. Without a transaction it is immediate.
 */
public class TransactionalEntityChangePublisher implements EntityChangePublisher {

    private final ApplicationEventPublisher events;

    public TransactionalEntityChangePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(EntityChangedEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publishEvent(event);
                }
            });
        } else {
            events.publishEvent(event);
        }
    }
}
