package su.onno.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.cluster.ClusterEvent;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Postgres {@code LISTEN}/{@code NOTIFY} bus on a real database: a change published by one node
 * reaches another node's subscriber, the publisher does not receive its own echo, and the listener
 * reconnects after its connection is dropped. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresClusterEventBusPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private final ObjectMapper mapper = new ObjectMapper();
    private PostgresClusterEventBus nodeA;
    private PostgresClusterEventBus nodeB;

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private PostgresClusterEventBus start(String nodeId) {
        OnnoClusterProperties props = new OnnoClusterProperties();
        props.setNodeId(nodeId);
        props.setPollTimeout(Duration.ofMillis(500)); // tighten shutdown/reconnect latency for the test
        PostgresClusterEventBus bus = new PostgresClusterEventBus(dataSource(), mapper, props);
        bus.afterPropertiesSet();
        return bus;
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) {
            nodeA.destroy();
        }
        if (nodeB != null) {
            nodeB.destroy();
        }
    }

    @Test
    void deliversAcrossNodesAndFiltersTheOriginsOwnEcho() {
        nodeA = start("node-A");
        nodeB = start("node-B");
        List<ClusterEvent> onB = new CopyOnWriteArrayList<>();
        List<ClusterEvent> onA = new CopyOnWriteArrayList<>();
        nodeB.subscribe(onB::add);
        nodeA.subscribe(onA::add);

        // Retry-publish until B is listening (no readiness signal); once it is, the NOTIFY arrives.
        awaitDelivered(nodeA, onB, "Customers");

        assertThat(onB).extracting(e -> ((ClusterEvent.EntityChanged) e).entityName()).contains("Customers");
        // A published it, so A's LISTEN echoes it back — but the self-filter drops it.
        assertThat(onA).isEmpty();
    }

    @Test
    void reconnectsAfterTheListenerConnectionIsDropped() {
        nodeA = start("node-A");
        nodeB = start("node-B");
        List<ClusterEvent> onB = new CopyOnWriteArrayList<>();
        nodeB.subscribe(onB::add);

        awaitDelivered(nodeA, onB, "Warmup");          // establish delivery
        terminateListenerBackends();                    // kill B's LISTEN connection
        onB.clear();

        // After backoff + re-LISTEN, delivery resumes.
        awaitDelivered(nodeA, onB, "AfterReconnect");
        assertThat(onB).extracting(e -> ((ClusterEvent.EntityChanged) e).entityName()).contains("AfterReconnect");
    }

    /** Publish a uniquely-named change from {@code publisher} every 250ms until {@code sink} sees it (≤20s). */
    private void awaitDelivered(PostgresClusterEventBus publisher, List<ClusterEvent> sink, String entityName) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            publisher.publish(ClusterEvent.entityChanged("created", "catalog", entityName, "id-1", "C-1"));
            if (sink.stream().anyMatch(e -> e instanceof ClusterEvent.EntityChanged ec && entityName.equals(ec.entityName()))) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError("'" + entityName + "' was not delivered within 20s");
    }

    private void terminateListenerBackends() {
        Jdbi admin = Jdbi.create(dataSource());
        admin.useHandle(handle -> handle.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        + "WHERE query ILIKE 'LISTEN%' AND pid <> pg_backend_pid()"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
