package su.onno.spring;

import su.onno.spring.fixtures.timetypes.TimedShiftRepository;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;

@SpringJUnitConfig(TimeTypesRepositoryH2IT.Config.class)
class TimeTypesRepositoryH2IT extends TimeTypesRepositoryContract {

    @Configuration
    @EnableJdbcRepositories(basePackageClasses = TimedShiftRepository.class)
    static class Config extends BaseConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:time_types_repo;DB_CLOSE_DELAY=-1");
            return ds;
        }
    }
}
