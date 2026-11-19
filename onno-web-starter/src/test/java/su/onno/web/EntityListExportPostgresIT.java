package su.onno.web;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.spring5.SpringConnectionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

/** Runs the export contract on an explicitly configured disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "ONNO_TEST_PG_URL", matches = ".+")
class EntityListExportPostgresIT extends EntityListExportTest {
    private Jdbi admin;
    private String schema;

    @Override
    Jdbi createJdbi() {
        String url = System.getenv("ONNO_TEST_PG_URL");
        String user = System.getenv("ONNO_TEST_PG_USER");
        String password = System.getenv("ONNO_TEST_PG_PASSWORD");
        admin = Jdbi.create(url, user, password);
        schema = "export_" + UUID.randomUUID().toString().replace("-", "");
        admin.useHandle(h -> h.execute("CREATE SCHEMA " + schema));
        String separator = url.contains("?") ? "&" : "?";
        dataSource = new DriverManagerDataSource(url + separator + "currentSchema=" + schema, user, password);
        return Jdbi.create(new SpringConnectionFactory(dataSource));
    }

    @AfterEach
    void removeTestSchema() {
        if (admin != null && schema != null) admin.useHandle(h -> h.execute("DROP SCHEMA " + schema + " CASCADE"));
    }
}
