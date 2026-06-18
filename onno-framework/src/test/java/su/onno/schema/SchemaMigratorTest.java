package su.onno.schema;

import su.onno.fixtures.TestSecretAccount;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigratorTest {

    private Jdbi h2() {
        return Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private MetadataRegistry registry() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestSecretAccount.class));
        return registry;
    }

    private String columnType(Jdbi jdbi, String table, String column) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE UPPER(TABLE_NAME) = UPPER(:t) AND UPPER(COLUMN_NAME) = UPPER(:c)")
                .bind("t", table)
                .bind("c", column)
                .mapTo(String.class)
                .one());
    }

    private long columnLength(Jdbi jdbi, String table, String column) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE UPPER(TABLE_NAME) = UPPER(:t) AND UPPER(COLUMN_NAME) = UPPER(:c)")
                .bind("t", table)
                .bind("c", column)
                .mapTo(Long.class)
                .one());
    }

    @Test
    void addsMissingAttributeColumns() {
        Jdbi jdbi = h2();
        // Pre-existing table from an older model version: no attribute columns yet.
        jdbi.useHandle(h -> h.execute(
                "CREATE TABLE catalog_test_secret_accounts (_id UUID PRIMARY KEY, _code VARCHAR(9), "
                        + "_description VARCHAR(255), _deletion_mark BOOLEAN DEFAULT FALSE)"));

        new SchemaMigrator(registry()).executeAdditive(jdbi);

        assertThat(columnType(jdbi, "catalog_test_secret_accounts", "username"))
                .isEqualTo("CHARACTER VARYING");
        assertThat(columnLength(jdbi, "catalog_test_secret_accounts", "username"))
                .isEqualTo(100);
    }

    @Test
    void secretColumnAddedByMigrationIsWidenedToText() {
        Jdbi jdbi = h2();
        jdbi.useHandle(h -> h.execute(
                "CREATE TABLE catalog_test_secret_accounts (_id UUID PRIMARY KEY, _code VARCHAR(9), "
                        + "_description VARCHAR(255), _deletion_mark BOOLEAN DEFAULT FALSE)"));

        new SchemaMigrator(registry()).executeAdditive(jdbi);

        // Secret values are stored encrypted, so the migrated column must be TEXT —
        // the same widening fresh schema generation applies — not the attribute's
        // declared VARCHAR(100). H2 represents TEXT as an effectively unbounded
        // CHARACTER VARYING, so assert the length is far beyond the declared 100.
        assertThat(columnLength(jdbi, "catalog_test_secret_accounts", "ws_password"))
                .isGreaterThan(65_535);
    }
}
