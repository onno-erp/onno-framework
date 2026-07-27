package su.onno.process;

/** Cause of one durable process transition. */
public enum ProcessTransitionType {
    START,
    HUMAN_TASK,
    AUTOMATIC,
    DECISION,
    TIMER,
    FORK,
    JOIN,
    SUBPROCESS,
    MIGRATION,
    CANCELLATION
}
