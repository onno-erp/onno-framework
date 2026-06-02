# onec-kafka-starter

Spring Boot starter that bridges the oneC framework's transactional **outbox** to Kafka and,
optionally, consumes events back through a de-duplicating **inbox**. Domain events declared on your
documents land in the `onec_outbox` table inside the same transaction that writes the document; this
starter relays those rows to a Kafka topic as [CloudEvents](https://cloudevents.io/), so publishing
is atomic with the business write.

It also ships a small `RemoteRefClient` for resolving entity references against other oneC services
listed in config.

## How events get into the outbox

Annotate a document with `@DomainEvent` (repeatable). When the framework writes/posts/deletes that
document, `PostingEngine` / the framework starter's save & delete callbacks append a row to
`onec_outbox` in the same transaction:

```java
@DomainEvent(name = "rentals.booking.confirmed", when = EventTiming.AFTER_POST)
@DomainEvent(name = "rentals.booking.deleted",   when = EventTiming.AFTER_DELETE)
public class Booking extends DocumentObject { ... }
```

`when` is one of `AFTER_WRITE` (default), `AFTER_POST`, or `AFTER_DELETE`. The outbox payload the
framework writes is a small JSON object `{"documentType":"<fqcn>","documentId":"<id>"}`; `event.name()`
becomes the event type. This starter is what carries those rows onward to Kafka — without it the rows
just accumulate.

## Enabling

The outbound relay auto-configures when **all** of these hold:

- `spring-kafka` is on the classpath (`KafkaTemplate`), and a `KafkaTemplate<String,String>` bean exists;
- an `OutboxWriter` bean exists (provided by the oneC framework starter);
- `onec.kafka.enabled` is `true` (the default — it is on unless you turn it off).

```yaml
onec:
  kafka:
    enabled: true
    service-name: rentals-service       # used as CloudEvent 'source' and consumer group prefix
    topic: onec.domain-events           # all outbound events go here
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

That wires three beans: `ServiceRegistry`, `RemoteRefClient`, and `OutboxRelay`.

> **Gotcha — you must drive the relay yourself.** Unlike some sibling starters, this one does **not**
> register a scheduler. `OutboxRelay` is exposed as a bean but nothing calls it automatically. Pump it
> from your own scheduled job (or any trigger):
>
> ```java
> @Component
> class OutboxRelayJob {
>     private final OutboxRelay relay;
>     OutboxRelayJob(OutboxRelay relay) { this.relay = relay; }
>
>     @Scheduled(fixedDelay = 5000)
>     void flush() { relay.relayPending(); }   // returns the number of events sent
> }
> ```
>
> `relayPending()` reads up to `relay-batch-size` rows with status `NEW`, sends each to Kafka keyed by
> the aggregate id, then marks it `PUBLISHED`. Send and mark are sequential, so a crash between the two
> can re-publish an event — consumers must be idempotent (the inbox below handles this).

## What gets published

Each pending outbox row is serialized as a JSON CloudEvent and sent to `onec.kafka.topic`, keyed by
`aggregateId` (so events for one aggregate stay ordered within a partition):

| CloudEvent field | Source |
|------------------|--------|
| `specversion` | `"1.0"` |
| `id` | outbox row id (UUID, as string) |
| `source` | `onec.kafka.service-name` |
| `type` | the outbox `eventType` (i.e. the `@DomainEvent` `name`) |
| `subject` | `<aggregateType>/<aggregateId>` |
| `time` | relay time (`OffsetDateTime.now()`) |
| `datacontenttype` | `"application/json"` |
| `data` | the raw outbox payload JSON, as a string |

Keys and values are plain strings — configure your producer for `StringSerializer` (this starter does
not register a serializer; it relies on your `KafkaTemplate`/`spring.kafka.producer` config).

## Consuming (inbound)

Opt in with `onec.kafka.inbound.enabled=true`. This auto-configures a
`ConcurrentMessageListenerContainer` (string key/value deserializers) that:

1. decodes the CloudEvents envelope into an `InboundEvent`;
2. de-duplicates by CloudEvent `id` via the **inbox** (`onec_inbox` table, auto-created on a
   `DataSource` if present);
3. dispatches to every `EventHandler` bean whose `supports(eventType)` returns true.

Register handlers as beans:

```java
@Component
class BookingConfirmedHandler implements EventHandler {
    public boolean supports(String type) { return "rentals.booking.confirmed".equals(type); }
    public void handle(InboundEvent e) {
        // e.data() is the raw JSON payload string — deserialize into your own type
    }
}
```

Failure handling: a handler that throws marks the inbox row `FAILED` and then either publishes the
record to `dead-letter-topic` (if set) and commits, or rethrows so the container redelivers. Malformed
CloudEvents are dead-lettered (or dropped if no DLT is configured). Handlers should be idempotent — the
inbox dedupes by id, but a handler can still be retried after partial work.

> **Gotcha — inbox needs a `DataSource`.** Without one, no `Inbox` bean is created and inbound
> de-duplication is disabled (every delivery is processed). With Kafka's at-least-once delivery that
> means duplicates reach your handlers.

## Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onec.kafka.enabled` | `true` | Master switch for the outbound relay beans. |
| `onec.kafka.service-name` | `onec-service` | CloudEvent `source`; also the prefix for the default inbound group id. |
| `onec.kafka.topic` | `onec.domain-events` | Topic the relay publishes to (and the inbound default when no topics are listed). |
| `onec.kafka.relay-batch-size` | `100` | Max outbox rows drained per `relayPending()` call. |
| `onec.kafka.remote-services` | `{}` | Map of `name -> baseUrl` resolved by `RemoteRefClient`. |
| `onec.kafka.inbound.enabled` | `false` | Turn on the consumer. |
| `onec.kafka.inbound.topics` | `[]` | Topics to consume; empty falls back to `onec.kafka.topic`. |
| `onec.kafka.inbound.group-id` | — | Consumer group; blank defaults to `<service-name>-inbound`. |
| `onec.kafka.inbound.concurrency` | `1` | Listener container concurrency. |
| `onec.kafka.inbound.auto-offset-reset` | `latest` | Kafka `auto.offset.reset`. |
| `onec.kafka.inbound.dead-letter-topic` | — | When set, failed/malformed messages are sent here instead of being redelivered. |

## RemoteRefClient

For resolving an entity owned by another oneC service over HTTP. Map services in config:

```yaml
onec:
  kafka:
    remote-services:
      catalog-service: http://catalog:8080
```

```java
@Autowired RemoteRefClient remoteRef;
Map<?, ?> product = remoteRef.resolve("catalog-service", "products", productId);
// GET http://catalog:8080/products/{id}
```

Unknown service names throw `IllegalArgumentException`.
