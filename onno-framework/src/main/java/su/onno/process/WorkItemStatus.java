package su.onno.process;

/** Lifecycle of one durable human work item. */
public enum WorkItemStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    CANCELLED
}
