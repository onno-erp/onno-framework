package su.onno.ui;

import org.jdbi.v3.core.Jdbi;

/** {@link TimeTypesRoundTripContract} on the embedded H2 database. */
class TimeTypesRoundTripH2Test extends TimeTypesRoundTripContract {

    @Override
    Jdbi jdbi() {
        return Jdbi.create("jdbc:h2:mem:time_types;DB_CLOSE_DELAY=-1");
    }
}
