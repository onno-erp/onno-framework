package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RecordRoute} is the one reading of a record link carried as a string. */
class RecordRouteTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void aRecordRouteParsesInEveryShape() {
        for (String route : List.of("catalogs/Tenants/" + ID, "/catalogs/Tenants/" + ID,
                "/catalogs/Tenants/" + ID + "/", "catalogs/Tenants/" + ID + "?tab=history",
                "catalogs/Tenants/" + ID + "#comments", "catalogs/Tenants/" + ID + "/lines")) {
            assertThat(RecordRoute.parse(route)).as(route).contains(new RecordRoute("catalogs", "Tenants", ID));
        }
        assertThat(RecordRoute.parse("documents/Orders/" + ID)).contains(new RecordRoute("documents", "Orders", ID));
    }

    @Test
    void routesAddressingNoRecordAreEmpty() {
        for (String route : java.util.Arrays.asList(null, "", "/", "/reports/2026/q1", "catalogs/Tenants",
                "catalogs/Tenants/new", "catalogs/Tenants?focus=" + ID, "registers/Stock/" + ID,
                "Catalogs/Tenants/" + ID, "catalogs/Tenants/SO-001")) {
            assertThat(RecordRoute.parse(route)).as("%s", route).isEmpty();
        }
    }

    @Test
    void theEntitySegmentIsDecodedOnceKeepingPlusLiteral() {
        assertThat(RecordRoute.parse("catalogs/Ra%20Tenants/" + ID).orElseThrow().name()).isEqualTo("Ra Tenants");
        assertThat(RecordRoute.parse("catalogs/%D0%9A%D0%BB/" + ID).orElseThrow().name()).isEqualTo("Кл");
        assertThat(RecordRoute.parse("catalogs/R+D%20Projects/" + ID).orElseThrow().name()).isEqualTo("R+D Projects");
        assertThat(RecordRoute.parse("catalogs/100%/" + ID).orElseThrow().name()).isEqualTo("100%");
    }

    @Test
    void uuidOrNullAcceptsOnlyUuids() {
        assertThat(RecordRoute.uuidOrNull(ID)).isEqualTo(ID);
        assertThat(RecordRoute.uuidOrNull(ID.toString())).isEqualTo(ID);
        assertThat(RecordRoute.uuidOrNull("new")).isNull();
        assertThat(RecordRoute.uuidOrNull(null)).isNull();
    }
}
