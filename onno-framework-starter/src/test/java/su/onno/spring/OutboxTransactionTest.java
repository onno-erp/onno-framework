package su.onno.spring;

import su.onno.messaging.OutboxWriter;
import su.onno.metadata.MetadataRegistry;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxTransactionTest {

    @Test
    void appendParticipatesInSpringTransactionRollback() {
        DataSource dataSource = dataSource();
        OnnoAutoConfiguration configuration = new OnnoAutoConfiguration();
        Jdbi jdbi = configuration.jdbi(dataSource);
        new SchemaGenerator(new MetadataRegistry()).execute(jdbi);
        OutboxWriter writer = configuration.outboxWriter(jdbi);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            writer.append("Order", "order-1", "OrderChanged", "{}");
            status.setRollbackOnly();
        });

        long rows = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT COUNT(*) FROM onno_outbox")
                .mapTo(Long.class)
                .one());
        assertThat(rows).isZero();
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }
}
