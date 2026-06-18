package su.onno.mail.outbox;

import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link MailOutboxContract} on real PostgreSQL — exercises the {@code ON CONFLICT}
 * insert and the {@code UPDATE ... RETURNING}/{@code FOR UPDATE SKIP LOCKED} claim,
 * neither of which runs on H2. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class MailOutboxPostgresIT extends MailOutboxContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    protected Jdbi createJdbi() {
        return Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
