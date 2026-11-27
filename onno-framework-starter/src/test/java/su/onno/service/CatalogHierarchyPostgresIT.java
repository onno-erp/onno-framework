package su.onno.service;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The same repository/command contract on PostgreSQL, isolated in a fresh schema per test. */
class CatalogHierarchyPostgresIT extends CatalogHierarchyWriteTest {

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String username;
    private static String password;
    private String schema;

    @BeforeAll static void postgres() {
        // A disposable external server also lets this contract run on machines without Docker.
        url = System.getenv("ONNO_TEST_POSTGRES_URL");
        if (url != null && !url.isBlank()) {
            username = System.getenv("ONNO_TEST_POSTGRES_USER");
            password = System.getenv("ONNO_TEST_POSTGRES_PASSWORD");
        } else {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "PostgreSQL requires Docker or an external test server");
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
    }

    @Override protected DataSource dataSource() {
        schema = "hierarchy_" + UUID.randomUUID().toString().replace("-", "");
        Jdbi.create(new DriverManagerDataSource(url, username, password))
                .useHandle(handle -> handle.execute("CREATE SCHEMA " + schema));
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, username, password);
    }

    @AfterEach void dropSchema() {
        if (schema != null) {
            Jdbi.create(new DriverManagerDataSource(url, username, password))
                    .useHandle(handle -> handle.execute("DROP SCHEMA " + schema + " CASCADE"));
        }
    }

    @AfterAll static void stopPostgres() {
        if (container != null) container.stop();
    }
}
