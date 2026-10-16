package su.onno.spring;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.events.EntityChangePublisher;
import su.onno.events.EntityChangedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntityChangePublisherTransactionTest {
    private final List<Object> captured = new ArrayList<>();
    private final EntityChangePublisher publisher =
            new OnnoAutoConfiguration().entityChangePublisher(captured::add);
    private final EntityChangedEvent event = new EntityChangedEvent(
            EntityChangedEvent.CREATED, EntityChangedEvent.CATALOG, "Services", UUID.randomUUID(), "S-1");

    @Test
    void publishesOnlyAfterCommit() {
        transaction().executeWithoutResult(status -> {
            publisher.publish(event);
            assertThat(captured).isEmpty();
        });
        assertThat(captured).containsExactly(event);
    }

    @Test
    void rollbackDiscardsEvent() {
        transaction().executeWithoutResult(status -> {
            publisher.publish(event);
            assertThat(captured).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(captured).isEmpty();
    }

    @Test
    void publishesImmediatelyWithoutTransaction() {
        publisher.publish(event);
        assertThat(captured).containsExactly(event);
    }

    private static TransactionTemplate transaction() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
