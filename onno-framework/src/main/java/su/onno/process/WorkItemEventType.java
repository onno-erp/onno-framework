package su.onno.process;

/** Durable audit events in the lifecycle of a human task. */
public enum WorkItemEventType {
    CREATED,
    CLAIMED,
    DELEGATED,
    COMPLETED,
    CANCELLED
}
