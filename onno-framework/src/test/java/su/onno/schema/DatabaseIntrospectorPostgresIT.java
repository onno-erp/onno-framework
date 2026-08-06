package su.onno.schema;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseIntrospectorPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void ignoresTablesAndColumnsOutsideTheCurrentSchema() {
        Jdbi jdbi = Jdbi.create(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE shared_table (own_column VARCHAR(50))");
            handle.execute("CREATE SCHEMA shadow");
            handle.execute("CREATE TABLE shadow.shared_table (foreign_column VARCHAR(50))");
            handle.execute("CREATE TABLE shadow.foreign_only (id UUID PRIMARY KEY)");
        });

        DatabaseIntrospector.DbState state = jdbi.withHandle(DatabaseIntrospector::read);

        assertThat(state.columns("shared_table")).containsExactly("OWN_COLUMN");
        assertThat(state.hasTable("foreign_only")).isFalse();
    }
}
