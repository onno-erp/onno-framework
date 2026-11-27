package su.onno.service;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

/** Run the same sorting contract against a disposable PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "ONNO_TEST_PG_URL", matches = ".+")
class ListSortPostgresIT extends ListSortQueryTest {
    private Jdbi admin;
    private String schema;

    @Override
    Jdbi createJdbi() {
        String url = System.getenv("ONNO_TEST_PG_URL");
        String user = System.getenv("ONNO_TEST_PG_USER");
        String password = System.getenv("ONNO_TEST_PG_PASSWORD");
        admin = Jdbi.create(url, user, password);
        schema = "sort_" + UUID.randomUUID().toString().replace("-", "");
        admin.useHandle(h -> h.execute("CREATE SCHEMA " + schema));
        return Jdbi.create(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    }

    @AfterEach
    void removeTestSchema() {
        if (admin != null && schema != null) admin.useHandle(h -> h.execute("DROP SCHEMA " + schema + " CASCADE"));
    }
}
