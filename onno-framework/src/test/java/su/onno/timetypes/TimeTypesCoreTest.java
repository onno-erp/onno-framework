package su.onno.timetypes;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;
import su.onno.query.Cursor;
import su.onno.repository.InformationRegisterPersistence;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core support for {@code LocalTime} and {@code Instant} fields (#548): column types, the snapshot
 * diff that migrates onto them, information-register mapping, and keyset cursors over them.
 */
class TimeTypesCoreTest {

    @Catalog(name = "Core Shifts", autoNumber = false)
    public static class CoreShift extends CatalogObject {
        @Attribute private LocalTime startsAt;
        @Attribute private Instant openedAt;
    }

    @InformationRegister(name = "CoreOpeningHours", periodicity = Periodicity.NONE)
    public static class CoreOpeningHours extends InformationRecord {
        @Dimension private UUID branch;
        @Dimension private LocalTime slot;
        @Attribute private LocalTime closesAt;
        @Attribute private Instant confirmedAt;
    }

    @Test
    void sqlTypes() {
        assertThat(SchemaGenerator.sqlType(LocalTime.class, 0, 0, 0)).isEqualTo("TIME");
        assertThat(SchemaGenerator.sqlType(Instant.class, 0, 0, 0)).isEqualTo("TIMESTAMP WITH TIME ZONE");
    }

    @Test
    void schemaCreatesTypedColumns_andStoresAbsoluteMoments() {
        Jdbi jdbi = h2();
        MetadataRegistry registry = new MetadataRegistry();
        CatalogDescriptor desc = new MetadataScanner(new DefaultNamingStrategy()).scan(CoreShift.class);
        registry.registerCatalog(desc);
        new SchemaGenerator(registry).execute(jdbi);

        Map<String, String> types = jdbi.withHandle(h -> h.createQuery(
                        "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE UPPER(TABLE_NAME) = UPPER(:t)")
                .bind("t", desc.tableName())
                .reduceRows(new java.util.HashMap<String, String>(), (map, row) -> {
                    map.put(row.getColumn("COLUMN_NAME", String.class).toLowerCase(),
                            row.getColumn("DATA_TYPE", String.class));
                    return map;
                }));
        assertThat(types.get("starts_at")).isEqualTo("TIME");
        assertThat(types.get("opened_at")).isEqualTo("TIMESTAMP WITH TIME ZONE");

        // The same moment written with two different offsets is one stored value.
        jdbi.useHandle(h -> {
            h.createUpdate("INSERT INTO " + desc.tableName() + " (_id, _code, starts_at, opened_at) "
                            + "VALUES (:id, 'a', :t, :i)")
                    .bind("id", UUID.randomUUID()).bind("t", LocalTime.of(9, 30))
                    .bind("i", Instant.parse("2026-01-16T01:00:00Z")).execute();
            h.createUpdate("INSERT INTO " + desc.tableName() + " (_id, _code, opened_at) "
                            + "VALUES (:id, 'b', :i)")
                    .bind("id", UUID.randomUUID())
                    .bind("i", OffsetDateTime.parse("2026-01-16T04:00:00+03:00")).execute();
        });
        List<Instant> stored = jdbi.withHandle(h -> h.createQuery(
                "SELECT opened_at FROM " + desc.tableName() + " ORDER BY _code").mapTo(Instant.class).list());
        assertThat(stored).containsOnly(Instant.parse("2026-01-16T01:00:00Z"));
        LocalTime startsAt = jdbi.withHandle(h -> h.createQuery(
                "SELECT starts_at FROM " + desc.tableName() + " WHERE _code = 'a'").mapTo(LocalTime.class).one());
        assertThat(startsAt).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void informationRegister_roundTripsDimensionAndAttributes() {
        Jdbi jdbi = h2();
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerInformationRegister(new MetadataScanner(new DefaultNamingStrategy())
                .scanInformationRegister(CoreOpeningHours.class));
        new SchemaGenerator(registry).execute(jdbi);
        InformationRegisterDescriptor desc = registry.getInformationRegisterDescriptor(CoreOpeningHours.class);
        var persistence = new InformationRegisterPersistence<CoreOpeningHours>(jdbi, desc);

        UUID branch = UUID.randomUUID();
        CoreOpeningHours record = new CoreOpeningHours();
        record.branch = branch;
        record.slot = LocalTime.of(9, 0);
        record.closesAt = LocalTime.of(18, 30);
        record.confirmedAt = Instant.parse("2026-03-01T10:20:00Z");
        persistence.write(record);

        // Same dimension tuple (including the LocalTime dimension) upserts rather than duplicating.
        record.closesAt = LocalTime.of(20, 0);
        persistence.write(record);

        List<CoreOpeningHours> rows = persistence.getRecords(Collections.emptyMap());
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.slot).isEqualTo(LocalTime.of(9, 0));
            assertThat(r.closesAt).isEqualTo(LocalTime.of(20, 0));
            assertThat(r.confirmedAt).isEqualTo(Instant.parse("2026-03-01T10:20:00Z"));
        });
    }

    @Test
    void cursor_roundTripsTimeAndOffsetValuesAsBindableJdbcTypes() {
        UUID id = UUID.randomUUID();

        Cursor time = Cursor.decode(new Cursor("starts_at", false, Time.valueOf("07:00:00"), id).encode());
        assertThat(time.value()).isEqualTo(Time.valueOf("07:00:00"));
        Cursor localTime = Cursor.decode(new Cursor("starts_at", false, LocalTime.of(7, 0), id).encode());
        assertThat(localTime.value()).isEqualTo(Time.valueOf("07:00:00"));

        // H2 hands back an OffsetDateTime for TIMESTAMP WITH TIME ZONE; it must bind as the same
        // moment a PostgreSQL Timestamp would.
        OffsetDateTime odt = OffsetDateTime.parse("2026-01-10T03:00:00+03:00");
        Cursor instant = Cursor.decode(new Cursor("opened_at", false, odt, id).encode());
        assertThat(instant.value()).isInstanceOf(Timestamp.class);
        assertThat(((Timestamp) instant.value()).toInstant()).isEqualTo(odt.toInstant());
    }

    private static Jdbi h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:timetypes" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }
}
