package su.onno.ui.migration;

import su.onno.metadata.MetadataRegistry;
import su.onno.migration.MigrationContext;
import su.onno.repository.EnumerationPersistence;
import su.onno.schema.SqlDialect;
import su.onno.ui.media.MediaStorage;
import su.onno.ui.media.StoredMedia;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ui2MigrationToolTest {

    enum Status {
        NEW,
        DONE
    }

    @Test
    void tableHelpersBatchEnumImageAndGeoConversions() throws Exception {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:ui2_migration;DB_CLOSE_DELAY=-1");
        AtomicInteger mediaSequence = new AtomicInteger();
        MediaStorage storage = (content, filename, contentType, size) -> {
            int n = mediaSequence.incrementAndGet();
            byte[] bytes = content.readAllBytes();
            assertThat(bytes).isNotEmpty();
            return new StoredMedia("legacy/" + n, "/api/media/legacy/" + n,
                    contentType, bytes.length, filename);
        };
        String dataUrl = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});

        jdbi.useHandle(handle -> {
            handle.execute("""
                    create table legacy_ui (
                        id integer primary key,
                        enum_value varchar(64),
                        image_value varchar(1024),
                        geo_value varchar(1024)
                    )
                    """);
            handle.createUpdate("""
                    insert into legacy_ui (id, enum_value, image_value, geo_value)
                    values (:id, :enum, :image, :geo)
                    """)
                    .bind("id", 1)
                    .bind("enum", "DONE")
                    .bind("image", dataUrl + "\nhttps://cdn.example/current.png")
                    .bind("geo", "55.7558, 37.6173")
                    .execute();
            handle.createUpdate("""
                    insert into legacy_ui (id, enum_value, image_value, geo_value)
                    values (:id, :enum, :image, :geo)
                    """)
                    .bind("id", 2)
                    .bind("enum", EnumerationPersistence.resolveId(Status.class, Status.NEW).toString())
                    .bind("image", "/api/media/current")
                    .bind("geo", "{\"type\":\"Point\",\"coordinates\":[37,55]}")
                    .execute();

            Ui2MigrationTool tool = new Ui2MigrationTool(
                    new MigrationContext(handle, new MetadataRegistry(), SqlDialect.H2),
                    storage,
                    1);

            assertThat(tool.migrateEnumNames(
                    "legacy_ui", "id", "enum_value", Status.class))
                    .isEqualTo(new Ui2MigrationTool.MigrationResult(2, 1));
            assertThat(tool.migrateDataUrlImages(
                    "legacy_ui", "id", "image_value"))
                    .isEqualTo(new Ui2MigrationTool.MigrationResult(2, 1));
            assertThat(tool.migrateGeoPoints(
                    "legacy_ui", "id", "geo_value"))
                    .isEqualTo(new Ui2MigrationTool.MigrationResult(2, 1));

            var migrated = handle.createQuery("""
                            select enum_value, image_value, geo_value
                              from legacy_ui
                             where id = 1
                            """)
                    .map((rs, ignored) -> new String[] {
                            rs.getString(1), rs.getString(2), rs.getString(3)
                    })
                    .one();
            assertThat(migrated[0])
                    .isEqualTo(EnumerationPersistence.resolveId(Status.class, Status.DONE).toString());
            assertThat(migrated[1])
                    .isEqualTo("/api/media/legacy/1\nhttps://cdn.example/current.png");
            assertThat(migrated[2])
                    .isEqualTo("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                            + "\"properties\":{},\"geometry\":{\"type\":\"Point\","
                            + "\"coordinates\":[37.6173,55.7558]}}]}");
        });

        assertThat(mediaSequence).hasValue(1);
    }

    @Test
    void sourceMetadataHelpersRequireExplicitCanonicalChoices() {
        assertThat(Ui2MigrationTool.canonicalEnumId(Status.class, "DONE"))
                .isEqualTo(EnumerationPersistence.resolveId(Status.class, Status.DONE));
        assertThat(Ui2MigrationTool.canonicalCurrencyFormat("currency", "rub"))
                .isEqualTo("currency:RUB");
        assertThat(Ui2MigrationTool.canonicalCurrencyFormat("currency:eur", null))
                .isEqualTo("currency:EUR");
        assertThat(Ui2MigrationTool.canonicalCurrencyFormat("decimal", null))
                .isEqualTo("decimal");
        assertThat(Ui2MigrationTool.canonicalIcon("bar-chart-3"))
                .isEqualTo("chart-column");
        assertThat(Ui2MigrationTool.canonicalIcon("activity"))
                .isEqualTo("activity");
        assertThat(Ui2MigrationTool.canonicalWidget("geolocation"))
                .isEqualTo("geojson");
        assertThat(Ui2MigrationTool.canonicalMapConfigKey("geoField"))
                .isEqualTo("geoJsonField");

        assertThatThrownBy(() -> Ui2MigrationTool.canonicalCurrencyFormat("currency", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
        assertThatThrownBy(() -> Ui2MigrationTool.canonicalEnumId(Status.class, "done"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void imageMigrationFailsFastWithoutStorageAndBadRowsCarryLocation() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:ui2_migration_failure;DB_CLOSE_DELAY=-1");
        jdbi.useHandle(handle -> {
            Ui2MigrationTool noMedia = new Ui2MigrationTool(
                    new MigrationContext(handle, new MetadataRegistry(), SqlDialect.H2));
            assertThatThrownBy(() -> noMedia.canonicalMediaValue(
                    "data:image/png;base64,AQID"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MediaStorage");

            handle.execute("create table bad_geo (id integer primary key, location varchar(40))");
            handle.execute("insert into bad_geo (id, location) values (7, 'north-ish')");
            assertThatThrownBy(() -> noMedia.migrateGeoPoints("bad_geo", "id", "location"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bad_geo.location")
                    .hasMessageContaining("id=7");
            assertThatThrownBy(() -> noMedia.migrateGeoPoints(
                    "bad_geo; drop table bad_geo", "id", "location"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe SQL identifier");
        });
    }
}
