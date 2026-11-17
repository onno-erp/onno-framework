package su.onno.service;

import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@link TimeTypesRoundTripContract} on PostgreSQL; skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class TimeTypesRoundTripPostgresIT extends TimeTypesRoundTripContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    javax.sql.DataSource dataSource() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
