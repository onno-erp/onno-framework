package su.onno.spring;

import su.onno.spring.fixtures.timetypes.TimedShiftRepository;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

/** Skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(TimeTypesRepositoryPostgresIT.Config.class)
class TimeTypesRepositoryPostgresIT extends TimeTypesRepositoryContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Configuration
    @EnableJdbcRepositories(basePackageClasses = TimedShiftRepository.class)
    static class Config extends BaseConfig {
        @Bean
        DataSource dataSource() {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(POSTGRES.getJdbcUrl());
            ds.setUser(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            return ds;
        }
    }
}
