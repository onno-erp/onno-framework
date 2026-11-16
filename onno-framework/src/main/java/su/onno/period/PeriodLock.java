package su.onno.period;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A closed period: documents in {@code scope} dated on or before {@code closedThrough} cannot be
 * created, changed, deleted, posted, reposted or unposted.
 *
 * @param scope         what the close applies to
 * @param closedThrough the last closed day (inclusive)
 * @param updatedAt     when the close date was last set
 * @param updatedBy     who set it
 * @param hard          a hard close admits nothing; a soft close (the default) still admits
 *                      {@linkplain PeriodAdjustment adjustment documents} and audited overrides
 */
public record PeriodLock(PeriodScope scope, LocalDate closedThrough, LocalDateTime updatedAt, String updatedBy,
                         boolean hard) {

    /** A soft close. */
    public PeriodLock(PeriodScope scope, LocalDate closedThrough, LocalDateTime updatedAt, String updatedBy) {
        this(scope, closedThrough, updatedAt, updatedBy, false);
    }

    /** {@code hard} or {@code soft}, for messages and the API. */
    public String state() {
        return hard ? "hard" : "soft";
    }

    /** Whether a document dated {@code date} falls inside the closed period. */
    public boolean covers(LocalDateTime date) {
        return date != null && !date.toLocalDate().isAfter(closedThrough);
    }
}
