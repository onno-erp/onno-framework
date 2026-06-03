package com.onec.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

import javax.sql.DataSource;

/**
 * Replaces Spring Boot's JdbcRepositoriesAutoConfiguration with one that excludes
 * RegisterRepository subtypes, and enables register repo scanning separately.
 */
@AutoConfiguration(before = JdbcRepositoriesAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnClass(EnableJdbcRepositories.class)
@Import({OnecJdbcRepositoriesRegistrar.class, RegisterRepositoriesAutoConfiguration.class})
public class OnecRepositoriesAutoConfiguration {
}
