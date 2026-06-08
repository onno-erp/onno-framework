package com.onec.desktop;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * When the shell launches the JVM it passes {@code --onec.desktop.home=<dir>}
 * pointing at the OS per-user app-data directory. In that case we relocate an
 * embedded H2 <em>file</em> datasource under {@code <home>/data} so the database
 * lives with the user's data rather than next to the binary (which, for a bundled
 * desktop app, is read-only and ephemeral).
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} because the rewrite must land
 * before {@code spring.datasource.url} is bound. When {@code onec.desktop.home}
 * is unset — normal {@code bootRun}/server runs — this does nothing, so dev is
 * untouched. Bold by design: in desktop mode the relocation is unconditional, no
 * opt-in flag.</p>
 *
 * <p>It also keeps the user logged in across launches. Every launch starts a fresh
 * JVM, so an in-memory HTTP session dies each time and the user would re-login. In
 * desktop mode we persist sessions in the (now file-backed) datasource via Spring
 * Session JDBC and hand the webview a long-lived, persistent cookie — so "stay
 * logged in" behaves like a native app. The session defaults go in at lowest
 * precedence, so an app can still override the timeout or cookie.</p>
 */
public class DesktopEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String H2_FILE_PREFIX = "jdbc:h2:file:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("onec.desktop.enabled", Boolean.class, true)) {
            return;
        }
        String home = environment.getProperty("onec.desktop.home", "");
        if (home.isBlank()) {
            return;
        }

        // Stay logged in across restarts. Persistent cookie (so the webview keeps it past quit)
        // + a long rolling timeout. The session *table* itself is created for every persistent
        // database (H2 file, PostgreSQL) by SessionSchemaEnvironmentPostProcessor — server and
        // desktop alike — so only the "stay logged in" defaults remain here. Overridable, so they
        // go in at lowest precedence.
        String url = environment.getProperty("spring.datasource.url", "");
        Map<String, Object> sessionDefaults = new LinkedHashMap<>();
        sessionDefaults.put("server.servlet.session.timeout", "30d");
        sessionDefaults.put("server.servlet.session.cookie.max-age", "30d");
        sessionDefaults.put("spring.session.timeout", "30d");
        environment.getPropertySources()
                .addLast(new MapPropertySource("onec-desktop-session", sessionDefaults));

        // Relocate an embedded H2 file datasource under the user's app-data home so the
        // database (and now the persisted sessions) live with the user's data rather
        // than next to the read-only binary. Highest precedence so it wins over yaml.
        if (url.startsWith(H2_FILE_PREFIX)) {
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("spring.datasource.url", relocate(url, home));
            environment.getPropertySources()
                    .addFirst(new MapPropertySource("onec-desktop-datasource", overrides));
        }
    }

    /**
     * {@code jdbc:h2:file:./data/example;DB_CLOSE_DELAY=-1} with home
     * {@code /Users/x/Library/Application Support/Rentals} becomes
     * {@code jdbc:h2:file:/Users/x/Library/Application Support/Rentals/data/example;DB_CLOSE_DELAY=-1}.
     */
    private String relocate(String url, String home) {
        String body = url.substring(H2_FILE_PREFIX.length());
        int semicolon = body.indexOf(';');
        String path = semicolon >= 0 ? body.substring(0, semicolon) : body;
        String params = semicolon >= 0 ? body.substring(semicolon) : "";

        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String dbName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;

        String normalizedHome = home.replace('\\', '/');
        if (normalizedHome.endsWith("/")) {
            normalizedHome = normalizedHome.substring(0, normalizedHome.length() - 1);
        }
        return H2_FILE_PREFIX + normalizedHome + "/data/" + dbName + params;
    }

    @Override
    public int getOrder() {
        // After Spring Boot's config-data processing so application.yaml is loaded.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
