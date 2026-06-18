package su.onno.desktop;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Ensures the Spring Session JDBC schema exists on persistent databases.
 *
 * <p>This starter puts {@code spring-session-jdbc} on the classpath, so HTTP sessions are
 * persisted in the application datasource in every mode (server <em>and</em> desktop). Spring
 * Boot only auto-creates the {@code SPRING_SESSION} tables for <em>in-memory embedded</em>
 * databases (the {@code initialize-schema=embedded} default). So on PostgreSQL <em>and</em> on a
 * file-backed H2 the tables are never created and the first session write fails with
 * {@code Table "SPRING_SESSION" not found} / {@code relation "spring_session" does not exist} —
 * e.g. a 500 on login from a plain {@code bootRun} with the default {@code jdbc:h2:file:} url.
 *
 * <p>For those datasources we force {@code initialize-schema=always} pointed at an idempotent
 * ({@code IF NOT EXISTS}) mirror of the upstream schema, so it is safe to run on every boot
 * against the persistent database. Defaults go in at lowest precedence so an application can
 * still override them. In-memory H2 needs nothing — Spring Boot initializes that itself.
 */
public class SessionSchemaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url", "");
        String schema;
        if (url.startsWith("jdbc:postgresql:")) {
            schema = "classpath:onno-desktop/session/schema-postgresql.sql";
        } else if (url.startsWith("jdbc:h2:") && !url.startsWith("jdbc:h2:mem:")) {
            // File/server H2 (jdbc:h2:file:, jdbc:h2:~/, jdbc:h2:./, …) is persistent and NOT
            // auto-initialised by Spring Boot; in-memory H2 is and is skipped here.
            schema = "classpath:onno-desktop/session/schema-h2.sql";
        } else {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.session.jdbc.initialize-schema", "always");
        defaults.put("spring.session.jdbc.schema", schema);
        environment.getPropertySources()
                .addLast(new MapPropertySource("onno-session-schema", defaults));
    }

    @Override
    public int getOrder() {
        // After Spring Boot's config-data processing so spring.datasource.url is resolved.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
