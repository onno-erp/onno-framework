package su.onno.ui;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.events.EntityChangedEvent;

class UiEventCommitTest {
    @Test void publishesOnlyAfterCommitAndNeverForRollbackButSupportsNonTransactionalEvents() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE conversations (id INT PRIMARY KEY)");
        List<Integer> visibleCounts = new ArrayList<>();
        UiEventPublisher publisher = new UiEventPublisher(new UiAccessService(null)) {
            @Override public void publish(String type, String entityType, String entityName, Object id, String naturalKey) {
                // Use a separate connection, just like the browser's concurrent list request.
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM conversations")) {
                    result.next();
                    visibleCounts.add(result.getInt(1));
                } catch (Exception ex) { throw new AssertionError(ex); }
            }
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(UiEventPublisher.class, () -> publisher);
            context.refresh();
            var event = new EntityChangedEvent("created", "catalog", "CrmConversations", UUID.randomUUID(), "CV-1");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO conversations VALUES (1)");
                context.publishEvent(event);
                assertThat(visibleCounts).isEmpty();
            });
            assertThat(visibleCounts).containsExactly(1);
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO conversations VALUES (2)");
                context.publishEvent(event);
                status.setRollbackOnly();
            });
            assertThat(visibleCounts).containsExactly(1);
            context.publishEvent(event);
            assertThat(visibleCounts).containsExactly(1, 1);
        } finally { publisher.shutdown(); }
    }
}
