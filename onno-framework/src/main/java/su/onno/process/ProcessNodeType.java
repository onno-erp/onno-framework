package su.onno.process;

/** Inspectable kind of a typed process-graph node. */
public enum ProcessNodeType {
    START,
    HUMAN_TASK,
    AUTOMATIC,
    DECISION,
    TIMER,
    PARALLEL_FORK,
    PARALLEL_JOIN,
    SUBPROCESS,
    END
}
