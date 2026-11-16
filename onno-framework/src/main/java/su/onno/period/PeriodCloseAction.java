package su.onno.period;

/** The kind of a {@link PeriodCloseAuditEntry}. */
public enum PeriodCloseAction {
    /** A close date was set or moved forward. */
    CLOSE,
    /** A close date was moved back or removed. */
    REOPEN,
    /** A change inside a closed period was let through for an authorized actor. */
    OVERRIDE,
    /** An {@linkplain PeriodAdjustment adjustment document} was written or posted inside a soft-closed period. */
    ADJUSTMENT
}
