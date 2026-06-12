package com.onec.migration;

import com.onec.metadata.MetadataRegistry;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class MigrationRunnerTest {

    private Jdbi h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        jdbi.useHandle(h -> h.execute("CREATE TABLE mig_log (v VARCHAR(20))"));
        return jdbi;
    }

    private AppMigration migration(String version, Consumer<MigrationContext> body) {
        return new AppMigration() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public void migrate(MigrationContext context) {
                body.accept(context);
            }
        };
    }

    private AppMigration logging(String version) {
        return migration(version, ctx -> ctx.execute("INSERT INTO mig_log VALUES ('" + version + "')"));
    }

    @Test
    void runsInVersionOrder_andOnlyOnce() {
        Jdbi jdbi = h2("mig_order");
        MigrationRunner runner = new MigrationRunner(new MetadataRegistry());

        List<String> executed = runner.run(jdbi, List.of(logging("10"), logging("2")));
        assertThat(executed).containsExactly("2", "10");

        List<String> again = runner.run(jdbi, List.of(logging("10"), logging("2")));
        assertThat(again).isEmpty();
        int rows = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM mig_log")
                .mapTo(Integer.class).one());
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void failingMigration_rollsBackWorkAndClaim() {
        Jdbi jdbi = h2("mig_fail");
        MigrationRunner runner = new MigrationRunner(new MetadataRegistry());
        AppMigration failing = migration("1", ctx -> {
            ctx.execute("INSERT INTO mig_log VALUES ('1')");
            throw new RuntimeException("boom");
        });

        assertThatThrownBy(() -> runner.run(jdbi, List.of(failing)))
                .hasMessageContaining("Migration 1");

        int rows = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM mig_log")
                .mapTo(Integer.class).one());
        assertThat(rows).isZero();

        // The claim rolled back with the work, so a fixed migration can run.
        List<String> executed = runner.run(jdbi, List.of(logging("1")));
        assertThat(executed).containsExactly("1");
    }

    @Test
    void duplicateVersions_rejected() {
        Jdbi jdbi = h2("mig_dup");
        MigrationRunner runner = new MigrationRunner(new MetadataRegistry());

        assertThatThrownBy(() -> runner.run(jdbi, List.of(logging("1"), logging("1"))))
                .hasMessageContaining("Duplicate migration version");
    }

    @Test
    void pending_listsUnappliedInOrder() {
        Jdbi jdbi = h2("mig_pending");
        MigrationRunner runner = new MigrationRunner(new MetadataRegistry());
        runner.run(jdbi, List.of(logging("1")));

        List<AppMigration> pending = runner.pending(jdbi, List.of(logging("3"), logging("1"), logging("2")));
        assertThat(pending).extracting(AppMigration::version).containsExactly("2", "3");
    }

    @Test
    void versionComparison_isSegmentWiseNumeric() {
        assertThat(MigrationRunner.compareVersions("2", "10")).isNegative();
        assertThat(MigrationRunner.compareVersions("1.2", "1.10")).isNegative();
        assertThat(MigrationRunner.compareVersions("1.0", "1.0")).isZero();
        assertThat(MigrationRunner.compareVersions("2026.06.002", "2026.06.001")).isPositive();
        assertThat(MigrationRunner.compareVersions("1.0.alpha", "1.0.beta")).isNegative();
    }
}
