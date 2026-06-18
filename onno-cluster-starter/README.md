# onno-cluster-starter

Cross-node delivery of entity-change events so a horizontally-scaled onno app — more than one instance
behind a load balancer — keeps its live UI in sync. A write on one node lights up the browsers
connected to every other node.

## Why it exists

The live UI uses Server-Sent Events: `UiEventPublisher` fans an `EntityChangedEvent` out to the
`SseEmitter`s **held in that JVM**. With a single node that is the whole story. With several nodes, a
browser connected to node B never sees a change made on node A, because the event never leaves A.

This starter closes that gap with a small SPI and a database-backed default:

- **`ClusterEventBus`** (`su.onno.cluster`, in `onno-framework`) — the pluggable transport. Swappable
  exactly like `MediaStorage`.
- **`PostgresClusterEventBus`** (this module) — the default, using Postgres `LISTEN`/`NOTIFY`. No
  infrastructure beyond the database you already run.
- **`NoOpClusterEventBus`** — the local-only fallback used on H2/dev or when disabled.

It is a **best-effort live-UI signal, not a guaranteed event log.** An event dropped during a
reconnect is fine — the browser re-fetches the affected surface on its next interaction. For durable,
exactly-once domain-event distribution between *services*, use the outbox relay (`onno-kafka-starter`).

## How it works

```
node A: write ──► EntityChangedEvent ──► local SSE clients (synchronous)
                                     └──► ClusterEntityChangeRelay ──► bus.publish ──► pg_notify
                                                                                          │
node B: LISTEN ◄──────────────────────────────────────────────────────────────────────┘
        getNotifications ──► ClusterUiBridge ──► UiEventPublisher.publish ──► node B's SSE clients
```

A received event is pushed straight to the SSE stream — it is **never re-published as a Spring
`ApplicationEvent`**. So business `@EventListener`s (cache revalidation, search indexing, post-hooks,
the kafka outbox) still run exactly once, on the node that made the change. A node filters out its own
`NOTIFY` echo by the event's `originNodeId`.

## Activation

On the classpath of an app whose datasource is **PostgreSQL**, the bus activates automatically. On H2
(dev/test) or when the PostgreSQL driver is absent, it falls back to the no-op bus and nothing changes.

It contributes a bus only with `@ConditionalOnMissingBean(ClusterEventBus.class)`, so to plug in a
different transport (Kafka, Redis, …) just expose your own `ClusterEventBus` bean:

```java
@Bean
ClusterEventBus clusterEventBus(/* your deps */) {
    return new MyRedisClusterEventBus(/* … */);
}
```

## Configuration (`onno.cluster.*`)

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. When off, a local-only no-op bus is used. |
| `channel` | `onno_cluster_events` | `LISTEN`/`NOTIFY` channel (bare identifier). |
| `node-id` | random UUID | This node's id, used to drop its own `NOTIFY` echoes. |
| `poll-timeout` | `5s` | How long the listener blocks before re-checking for shutdown. |
| `reconnect-backoff-max` | `30s` | Cap on the reconnect backoff after a dropped connection. |
| `max-payload-bytes` | `7000` | Soft cap below Postgres's 8000-byte `NOTIFY` limit; larger events drop their natural key, then degrade to a coarse `*` notice. |

## Operational notes

- The listener holds **one connection** from the datasource pool for its lifetime; size the pool with
  that in mind (e.g. `+1`).
- Multi-node scale-out also needs, outside this starter: a stable `onno.auth.session.remember-me.key`
  (or sticky/shared sessions), and a shared `MediaStorage` (the filesystem default is per-node). See
  the "Scaling out" section of `docs/ARCHITECTURE.md`.
