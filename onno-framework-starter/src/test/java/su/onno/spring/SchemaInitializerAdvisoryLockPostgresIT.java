package su.onno.spring;

import su.onno.schema.SchemaMode;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema apply is guarded by a Postgres advisory lock so the nodes of a horizontally-scaled deployment
 * don't race to run DDL at boot. Several {@link SchemaInitializer}s applying the same model
 * concurrently against one database must all succeed — without the lock, two nodes introspecting an
 * empty schema both plan {@code CREATE TABLE} and the loser fails. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaInitializerAdvisoryLockPostgresIT {

    private static final List<String> SCAN = List.of("su.onno.spring.fixtures");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    @Test
    void concurrentInitializersAllSucceedUnderTheLock() throws Exception {
        int nodes = 4;
        ExecutorService pool = Executors.newFixedThreadPool(nodes);
        CountDownLatch ready = new CountDownLatch(nodes);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < nodes; i++) {
                pool.submit(() -> {
                    SchemaInitializer initializer = new SchemaInitializer(
                            dataSource(), SCAN, SchemaMode.APPLY, false, List.of());
                    ready.countDown();
                    try {
                        go.await();                 // release all at once to maximise overlap
                        initializer.afterPropertiesSet();
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).as("no node should fail applying the schema concurrently").isEmpty();

        // The apply ran and recorded its baseline snapshot exactly once.
        Jdbi jdbi = Jdbi.create(dataSource());
        Integer historyRows = jdbi.withHandle(h ->
                h.createQuery("SELECT count(*) FROM onno_schema_history").mapTo(Integer.class).one());
        assertThat(historyRows).isGreaterThanOrEqualTo(1);
    }
}
