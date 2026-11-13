package su.onno.period;

/** The change a period close guards against. */
public enum PeriodOperation {
    CREATE("create"),
    UPDATE("change"),
    DELETE("delete"),
    POST("post"),
    REPOST("repost"),
    UNPOST("unpost"),
    /** A later document reposted by the chronological restoration of a backdated change. */
    RESTORE("recalculate");

    private final String verb;

    PeriodOperation(String verb) {
        this.verb = verb;
    }

    /** The verb used in rejection messages. */
    public String verb() {
        return verb;
    }
}
