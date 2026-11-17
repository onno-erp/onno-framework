package su.onno.service;

import org.jdbi.v3.core.Jdbi;

/** {@link TimeTypesRoundTripContract} on the embedded H2 database. */
class TimeTypesRoundTripH2Test extends TimeTypesRoundTripContract {

    @Override
    javax.sql.DataSource dataSource() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:time_types;DB_CLOSE_DELAY=-1");
    }
}
