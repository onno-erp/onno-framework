The outbound relay is **not** auto-scheduled — call `OutboxRelay.relayPending()` from your own
`@Scheduled` bean. Requires a `KafkaTemplate` and an `OutboxWriter` (from the core).
