package su.onno.schema;

import su.onno.annotations.Attribute;
import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An information register's key lives in a {@code UNIQUE} constraint, and its upserts name that
 * tuple in {@code ON CONFLICT}. When the two disagree the register keeps deduplicating on the key
 * the database has rather than the one the model declares — one row per dimension tuple, ever — so
 * a key change that the diff engine misses is silent data loss on every write after the upgrade,
 * visible only on databases that predate the change.
 */
class InformationRegisterKeyMigrationTest {

    @InformationRegister(name = "MigTaskStatuses")
    public static class NonPeriodic extends InformationRecord {
        @Dimension
        private UUID task;
        @Attribute(length = 32)
        private String status;
    }

    @InformationRegister(name = "MigTaskStatuses", periodicity = Periodicity.SECOND)
    public static class PerSecond extends InformationRecord {
        @Dimension
        private UUID task;
        @Attribute(length = 32)
        private String status;
    }

    @InformationRegister(name = "MigTaskStatuses", periodicity = Periodicity.SECOND)
    public static class PerSecondByAssignee extends InformationRecord {
        @Dimension
        private UUID task;
        @Dimension
        private UUID assignee;
        @Attribute(length = 32)
        private String status;
    }

    private static final String TABLE = "inforeg_mig_task_statuses";

    private Jdbi h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }

    private MigrationPlan apply(Jdbi jdbi, Class<?> registerClass) {
        return apply(jdbi, registerClass, false);
    }

    private MigrationPlan apply(Jdbi jdbi, Class<?> registerClass, boolean allowDestructive) {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerInformationRegister(
                new MetadataScanner(new DefaultNamingStrategy()).scanInformationRegister(registerClass));
        return new SchemaUpgrader(registry, SchemaMode.APPLY, allowDestructive).run(jdbi);
    }

    /** The column set of every UNIQUE constraint on the register table, upper-cased. */
    private List<Set<String>> uniqueKeys(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT LISTAGG(kcu.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY kcu.COLUMN_NAME) " +
                                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                                "  ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                                " AND kcu.TABLE_NAME = tc.TABLE_NAME " +
                                "WHERE tc.CONSTRAINT_TYPE = 'UNIQUE' AND tc.TABLE_NAME = :t " +
                                "GROUP BY tc.CONSTRAINT_NAME")
                .bind("t", TABLE.toUpperCase())
                .mapTo(String.class)
                .list()
                .stream()
                .map(columns -> Set.of(columns.split(",")))
                .toList());
    }

    private void insert(Jdbi jdbi, UUID task, LocalDateTime period, String status) {
        jdbi.useHandle(handle -> {
            if (period == null) {
                handle.execute("INSERT INTO " + TABLE + " (_id, task, status) VALUES (?, ?, ?)",
                        UUID.randomUUID(), task, status);
            } else {
                handle.execute("INSERT INTO " + TABLE + " (_id, _period, task, status) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), period, task, status);
            }
        });
    }

    private int rowCount(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + TABLE).mapTo(Integer.class).one());
    }

    @Test
    void freshRegister_getsTheDeclaredKeyAndDoesNotDriftOnTheNextBoot() {
        Jdbi jdbi = h2("inforeg_key_fresh");
        apply(jdbi, PerSecond.class);

        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "TASK"));
        assertThat(apply(jdbi, PerSecond.class).isEmpty()).isTrue();
    }

    @Test
    void periodicityAdded_replacesTheStaleKeyKeepingRows() {
        Jdbi jdbi = h2("inforeg_key_periodicity");
        apply(jdbi, NonPeriodic.class);
        UUID task = UUID.randomUUID();
        insert(jdbi, task, null, "OPEN");
        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("TASK"));

        MigrationPlan plan = apply(jdbi, PerSecond.class);

        // Widening the key can neither fail nor lose rows, so it applies without allow-destructive —
        // the upgrade path this exists for is exactly the one that would otherwise stay broken.
        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.ALTER_UNIQUE_KEY
                && !c.destructive());
        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "TASK"));
        assertThat(rowCount(jdbi)).isEqualTo(1);

        // The point of the key change: the same task can now hold a row per period.
        insert(jdbi, task, LocalDateTime.of(2026, 8, 20, 9, 0, 0), "IN_PROGRESS");
        insert(jdbi, task, LocalDateTime.of(2026, 8, 20, 9, 0, 1), "DONE");
        assertThat(rowCount(jdbi)).isEqualTo(3);
    }

    @Test
    void dimensionAdded_replacesTheStaleKey() {
        Jdbi jdbi = h2("inforeg_key_dimension");
        apply(jdbi, PerSecond.class);
        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "TASK"));

        apply(jdbi, PerSecondByAssignee.class);

        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "ASSIGNEE", "TASK"));
    }

    @Test
    void dimensionRemoved_isGatedBecauseTheNarrowerKeyMayNotHold() {
        Jdbi jdbi = h2("inforeg_key_narrowing");
        apply(jdbi, PerSecondByAssignee.class);

        MigrationPlan plan = apply(jdbi, PerSecond.class);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.ALTER_UNIQUE_KEY
                && c.destructive());
        // Skipped by default: dropping the assignee dimension can leave duplicate (_period, task) rows.
        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "ASSIGNEE", "TASK"));

        apply(jdbi, PerSecond.class, true);
        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "TASK"));
    }

    @Test
    void registerWithNoUniqueConstraintAtAll_getsOneAdded() {
        Jdbi jdbi = h2("inforeg_key_missing");
        jdbi.useHandle(handle -> handle.execute("""
                CREATE TABLE inforeg_mig_task_statuses (
                    _id UUID PRIMARY KEY,
                    _period TIMESTAMP,
                    task UUID,
                    status VARCHAR(32)
                )
                """));

        MigrationPlan plan = apply(jdbi, PerSecond.class, true);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.ALTER_UNIQUE_KEY
                && c.destructive());
        assertThat(uniqueKeys(jdbi)).containsExactly(Set.of("_PERIOD", "TASK"));
    }
}
