package su.onno.process;

/** Durable state of one execution token in a process instance. */
public enum ProcessTokenStatus {
    READY,
    WAITING_HUMAN,
    WAITING_TIMER,
    WAITING_JOIN,
    WAITING_SUBPROCESS,
    COMPLETED,
    CANCELLED
}
