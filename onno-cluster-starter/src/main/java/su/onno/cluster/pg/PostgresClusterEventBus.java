package su.onno.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.cluster.ClusterEvent;
import su.onno.cluster.ClusterEventBus;

import org.jdbi.v3.core.Jdbi;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * {@link ClusterEventBus} backed by Postgres {@code LISTEN}/{@code NOTIFY} — the default cross-node
 * transport, needing no infrastructure beyond the database the app already uses.
 *
 * <p>Publish is a short pooled {@code SELECT pg_notify(channel, json)}. Receive runs on one daemon
 * thread holding a dedicated connection that {@code LISTEN}s on the channel and blocks in
 * {@link PGConnection#getNotifications(int)}; on a dropped connection it reconnects with capped
 * exponential backoff. Events the node published itself are filtered out by
 * {@link ClusterEvent#originNodeId()} (Postgres echoes a {@code NOTIFY} back to the publisher's own
 * {@code LISTEN}, and the publisher already fanned the change out to its local SSE clients).
 *
 * <p>This is a best-effort live-UI signal: a notice missed during a reconnect is acceptable (browsers
 * re-fetch on their next interaction). The held listener connection costs one slot from the datasource
 * pool for the bus's lifetime.
 */
public class PostgresClusterEventBus implements ClusterEventBus, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostgresClusterEventBus.class);

    private static final long INITIAL_BACKOFF_MILLIS = 1000;

    private final DataSource dataSource;
    private final Jdbi publishJdbi;
    private final ObjectMapper mapper;

    private final String nodeId;
    private final String channel;
    private final int pollTimeoutMillis;
    private final long maxBackoffMillis;
    private final int maxPayloadBytes;

    private final List<Consumer<ClusterEvent>> sinks = new CopyOnWriteArrayList<>();
    private final ExecutorService listener = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onno-cluster-listener");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;
    private volatile Connection listenConnection;

    public PostgresClusterEventBus(DataSource dataSource, ObjectMapper mapper, OnnoClusterProperties props) {
        this.dataSource = dataSource;
        this.publishJdbi = Jdbi.create(dataSource);
        this.mapper = mapper;
        String configuredId = props.getNodeId();
        this.nodeId = (configuredId == null || configuredId.isBlank()) ? UUID.randomUUID().toString() : configuredId;
        this.channel = safeChannel(props.getChannel());
        this.pollTimeoutMillis = (int) Math.max(1, props.getPollTimeout().toMillis());
        this.maxBackoffMillis = Math.max(INITIAL_BACKOFF_MILLIS, props.getReconnectBackoffMax().toMillis());
        this.maxPayloadBytes = props.getMaxPayloadBytes();
    }

    @Override
    public void afterPropertiesSet() {
        listener.submit(this::listenLoop);
        log.info("onno-cluster: Postgres LISTEN/NOTIFY bus active on channel '{}' (node {}).", channel, nodeId);
    }

    @Override
    public void publish(ClusterEvent event) {
        String json = serialize(mapper, event.withOrigin(nodeId), maxPayloadBytes);
        if (json == null) {
            return;
        }
        try {
            publishJdbi.useHandle(handle -> handle.createUpdate("SELECT pg_notify(:channel, :payload)")
                    .bind("channel", channel)
                    .bind("payload", json)
                    .execute());
        } catch (RuntimeException e) {
            // Fail soft: a missed NOTIFY only costs peers a live refresh, which they recover on next fetch.
            log.debug("onno-cluster: failed to NOTIFY a {} change; peers may miss this live update: {}",
                    event.changeType(), e.toString());
        }
    }

    @Override
    public void subscribe(Consumer<ClusterEvent> sink) {
        sinks.add(sink);
    }

    private void listenLoop() {
        long backoff = INITIAL_BACKOFF_MILLIS;
        while (running) {
            try (Connection conn = dataSource.getConnection()) {
                listenConnection = conn;
                PGConnection pg = conn.unwrap(PGConnection.class);
                try (Statement statement = conn.createStatement()) {
                    statement.execute("LISTEN \"" + channel + "\"");
                }
                backoff = INITIAL_BACKOFF_MILLIS; // reset after a clean (re)connect
                log.debug("onno-cluster: listening on '{}'", channel);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(pollTimeoutMillis);
                    if (notifications == null) {
                        continue; // timed out with nothing; loop to re-check the running flag
                    }
                    for (PGNotification notification : notifications) {
                        dispatch(notification.getParameter());
                    }
                }
            } catch (SQLException e) {
                if (!running) {
                    break;
                }
                log.warn("onno-cluster: listener connection lost; reconnecting in {} ms ({})", backoff, e.toString());
                sleep(backoff);
                backoff = Math.min(backoff * 2, maxBackoffMillis);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                log.warn("onno-cluster: unexpected listener error; reconnecting in {} ms", backoff, e);
                sleep(backoff);
                backoff = Math.min(backoff * 2, maxBackoffMillis);
            } finally {
                listenConnection = null;
            }
        }
        log.debug("onno-cluster: listener loop stopped");
    }

    void dispatch(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        ClusterEvent event;
        try {
            event = mapper.readValue(payload, ClusterEvent.class);
        } catch (Exception e) {
            log.debug("onno-cluster: ignoring unparseable notification payload: {}", e.toString());
            return;
        }
        if (nodeId.equals(event.originNodeId())) {
            return; // our own echo — already fanned out to local SSE clients synchronously
        }
        for (Consumer<ClusterEvent> sink : sinks) {
            try {
                sink.accept(event);
            } catch (RuntimeException e) {
                log.debug("onno-cluster: sink threw handling a {} event: {}", event.changeType(), e.toString());
            }
        }
    }

    /**
     * Serialize to JSON, keeping under {@link #maxPayloadBytes} (Postgres caps a {@code NOTIFY} payload
     * at 8000 bytes). On overflow, drop the only unbounded field ({@code naturalKey}); if still too big,
     * degrade to a coarse {@code entityName="*"} notice the SSE clients already understand. Never throws.
     */
    static String serialize(ObjectMapper mapper, ClusterEvent event, int maxPayloadBytes) {
        try {
            String json = mapper.writeValueAsString(event);
            if (utf8Length(json) <= maxPayloadBytes) {
                return json;
            }
            ClusterEvent trimmed = new ClusterEvent(event.kind(), event.originNodeId(), event.changeType(),
                    event.entityType(), event.entityName(), event.id(), null);
            json = mapper.writeValueAsString(trimmed);
            if (utf8Length(json) <= maxPayloadBytes) {
                log.debug("onno-cluster: dropped oversized naturalKey from a {} event on {}",
                        event.changeType(), event.entityName());
                return json;
            }
            ClusterEvent coarse = new ClusterEvent(event.kind(), event.originNodeId(), event.changeType(),
                    event.entityType(), "*", null, null);
            json = mapper.writeValueAsString(coarse);
            if (utf8Length(json) <= maxPayloadBytes) {
                log.debug("onno-cluster: degraded an oversized {} event to a coarse '*' notice", event.changeType());
                return json;
            }
            log.debug("onno-cluster: dropping a {} event; even the coarse envelope exceeds {} bytes",
                    event.changeType(), maxPayloadBytes);
            return null;
        } catch (Exception e) {
            log.debug("onno-cluster: failed to serialize a cluster event: {}", e.toString());
            return null;
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * Restrict the channel to a bare identifier so it can be safely double-quoted in {@code LISTEN}
     * (matching the literal string {@code pg_notify} uses). Falls back to the default on anything else.
     */
    private static String safeChannel(String channel) {
        if (channel != null && channel.matches("[A-Za-z0-9_]+")) {
            return channel;
        }
        log.warn("onno-cluster: invalid channel '{}'; falling back to 'onno_cluster_events'", channel);
        return "onno_cluster_events";
    }

    /** The id this node stamps on published events and filters incoming echoes against. */
    public String nodeId() {
        return nodeId;
    }

    @Override
    public void destroy() {
        running = false;
        Connection conn = listenConnection;
        if (conn != null) {
            try {
                conn.close(); // unblock getNotifications by tearing down the socket
            } catch (SQLException ignored) {
                // shutting down anyway
            }
        }
        listener.shutdownNow();
    }
}
