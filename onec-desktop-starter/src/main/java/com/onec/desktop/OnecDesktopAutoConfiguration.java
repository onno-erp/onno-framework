package com.onec.desktop;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Wires desktop mode: exposes the manifest/readiness endpoints the Tauri shell
 * drives, and supplies a default {@link DesktopApp} when the application defines
 * none. Active whenever the starter is on a web classpath and
 * {@code onec.desktop.enabled} is not explicitly {@code false}.
 *
 * <p>H2 relocation into the OS app-data directory is handled earlier, by
 * {@link DesktopEnvironmentPostProcessor}, since it must run before the
 * datasource is bound.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication
@EnableConfigurationProperties(DesktopProperties.class)
@ConditionalOnProperty(prefix = "onec.desktop", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnecDesktopAutoConfiguration {

    /** A sensible default shell so dropping the starter in is enough to ship a window. */
    @Bean
    @ConditionalOnMissingBean
    public DesktopApp defaultDesktopApp(Environment environment) {
        String appName = environment.getProperty("spring.application.name", "onec");
        return spec -> spec.title(appName);
    }

    @Bean
    public DesktopManifest desktopManifest(DesktopApp desktopApp, Environment environment) {
        DesktopSpec spec = new DesktopSpec();
        desktopApp.configure(spec);
        return spec.build(environment.getProperty("spring.application.name", "onec"));
    }

    @Bean
    public DesktopManifestController desktopManifestController(DesktopManifest manifest) {
        return new DesktopManifestController(manifest);
    }
}
