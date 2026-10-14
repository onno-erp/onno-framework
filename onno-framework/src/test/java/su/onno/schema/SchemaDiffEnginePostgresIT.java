package su.onno.schema;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SchemaDiffEnginePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void dropTablesPlan_executesOnPostgresWhenParentAppearsBeforeChildInSnapshot() {
        Jdbi jdbi = Jdbi.create(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS diff_child_drop_test");
            handle.execute("DROP TABLE IF EXISTS diff_parent_drop_test");
            handle.execute("CREATE TABLE diff_parent_drop_test (id UUID PRIMARY KEY)");
            handle.execute("""
                    CREATE TABLE diff_child_drop_test (
                        id UUID PRIMARY KEY,
                        parent_id UUID REFERENCES diff_parent_drop_test(id)
                    )
                    """);
        });

        Map<String, SchemaSnapshot.TableSnapshot> snapshotTables = new LinkedHashMap<>();
        snapshotTables.put("diff_parent_drop_test", new SchemaSnapshot.TableSnapshot(Map.of()));
        snapshotTables.put("diff_child_drop_test", new SchemaSnapshot.TableSnapshot(Map.of()));
        SchemaSnapshot previous = new SchemaSnapshot(snapshotTables);

        MigrationPlan plan = jdbi.withHandle(handle -> new SchemaDiffEngine(SqlDialect.POSTGRESQL)
                .diff(new SchemaModel(List.of()), previous, DatabaseIntrospector.read(handle)));

        assertThat(plan.changes())
                .extracting(SchemaChange::table)
                .containsExactly("diff_child_drop_test", "diff_parent_drop_test");

        jdbi.useHandle(handle -> {
            for (SchemaChange change : plan.changes()) {
                for (String sql : change.sql()) {
                    handle.execute(sql);
                }
            }
        });

        DatabaseIntrospector.DbState after = jdbi.withHandle(DatabaseIntrospector::read);
        assertThat(after.hasTable("diff_child_drop_test")).isFalse();
        assertThat(after.hasTable("diff_parent_drop_test")).isFalse();
    }
}
