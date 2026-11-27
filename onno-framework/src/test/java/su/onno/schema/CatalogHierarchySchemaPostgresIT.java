package su.onno.schema;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The migration contract on PostgreSQL, isolated from all application schemas. */
class CatalogHierarchySchemaPostgresIT extends CatalogHierarchySchemaTest {
    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String username;
    private static String password;
    private String schema;

    @BeforeAll static void postgres() {
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

    @Override protected Jdbi database() {
        schema = "hierarchy_schema_" + UUID.randomUUID().toString().replace("-", "");
        Jdbi.create(url, username, password).useHandle(h -> h.execute("CREATE SCHEMA " + schema));
        return Jdbi.create(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
    }

    @AfterEach void dropSchema() {
        if (schema != null) {
            Jdbi.create(url, username, password).useHandle(h -> h.execute("DROP SCHEMA " + schema + " CASCADE"));
        }
    }

    @AfterAll static void stopPostgres() {
        if (container != null) container.stop();
    }
}
