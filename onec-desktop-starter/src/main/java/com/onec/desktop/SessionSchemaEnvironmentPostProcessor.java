package com.onec.desktop;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Ensures the Spring Session JDBC schema exists on PostgreSQL.
 *
 * <p>This starter puts {@code spring-session-jdbc} on the classpath, so HTTP sessions are
 * persisted in the application datasource in every mode (server <em>and</em> desktop). Spring
 * Boot only auto-creates the {@code SPRING_SESSION} tables for <em>embedded</em> databases
 * (the {@code initialize-schema=embedded} default), so on PostgreSQL the tables are never
 * created and the first session write fails with {@code relation "spring_session" does not
 * exist} — e.g. a 500 on login.
 *
 * <p>For a PostgreSQL datasource we therefore force {@code initialize-schema=always} and point
 * it at an idempotent ({@code IF NOT EXISTS}) mirror of the upstream schema, so it is safe to
 * run on every boot against the persistent database. These defaults go in at lowest precedence
 * so an application can still override them. H2 needs nothing here: Spring Boot initializes the
 * embedded schema itself, and the desktop file-database case is handled by
 * {@link DesktopEnvironmentPostProcessor}.
 */
public class SessionSchemaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:postgresql:")) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.session.jdbc.initialize-schema", "always");
        defaults.put("spring.session.jdbc.schema", "classpath:onec-desktop/session/schema-postgresql.sql");
        environment.getPropertySources()
                .addLast(new MapPropertySource("onec-session-postgresql", defaults));
    }

    @Override
    public int getOrder() {
        // After Spring Boot's config-data processing so spring.datasource.url is resolved.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
