package su.onno.history;

import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link RecordHistoryTest} on PostgreSQL: typed null binds, {@code TEXT} values, {@code UUID}
 * columns and the grouped keyset page all behave differently from H2.
 */
@Testcontainers(disabledWithoutDocker = true)
class RecordHistoryPostgresIT extends RecordHistoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    protected Jdbi createJdbi() {
        Jdbi jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // One container for the class: start every test from an empty history and fixture tables.
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS " + RecordHistory.TABLE);
            h.execute("DROP SCHEMA public CASCADE");
            h.execute("CREATE SCHEMA public");
        });
        return jdbi;
    }
}
