package com.onec.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.cluster.ClusterEventBus;
import com.onec.cluster.NoOpClusterEventBus;
import com.onec.schema.SqlDialect;
import com.onec.spring.OnecAutoConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wires the Postgres {@code LISTEN}/{@code NOTIFY} {@link ClusterEventBus} default.
 *
 * <p>Ordered {@code before} {@link OnecAutoConfiguration} so its {@code @ConditionalOnMissingBean} bus
 * registers ahead of that module's no-op fallback. It contributes a bus only when the PostgreSQL JDBC
 * driver is present <em>and</em> the live datasource is actually Postgres (detected via
 * {@link SqlDialect}); on H2 (dev/test) it returns a {@link NoOpClusterEventBus}, so the framework's
 * single-node behaviour is preserved. An application's own {@code ClusterEventBus} bean (e.g.
 * Kafka/Redis) wins over both via {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = OnecAutoConfiguration.class)
@ConditionalOnClass(name = "org.postgresql.PGConnection")
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "onec.cluster", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OnecClusterProperties.class)
public class OnecClusterAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OnecClusterAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ClusterEventBus.class)
    public ClusterEventBus onecClusterEventBus(DataSource dataSource, ObjectMapper objectMapper,
                                               OnecClusterProperties properties) {
        SqlDialect dialect;
        try (Connection connection = dataSource.getConnection()) {
            dialect = SqlDialect.detect(connection);
        } catch (SQLException e) {
            dialect = SqlDialect.H2;
        }
        if (dialect != SqlDialect.POSTGRESQL) {
            log.info("onec-cluster: datasource is {} (not Postgres); using local-only no-op bus.", dialect);
            return new NoOpClusterEventBus();
        }
        return new PostgresClusterEventBus(dataSource, objectMapper, properties);
    }
}
