package su.onno.model;

/**
 * How finely an {@code @InformationRegister} buckets its {@code _period} column.
 *
 * <p>A periodic register keys each row on {@code (_period, dimensions…)}: the written period is
 * floored to its bucket, and a second write landing in the same bucket with the same dimensions
 * <em>replaces</em> the first. That upsert is the point of the register — "the fact effective as of
 * this period" — but it means the granularity chosen here is also the finest interval at which two
 * distinct facts can coexist.
 *
 * <p>Pick the granularity the domain actually distinguishes. Prices and exchange rates change per
 * {@link #DAY} or coarser; an audit trail, a status history, or an intraday measurement series
 * needs {@link #SECOND} (1C's own finest information-register periodicity) or {@link #MINUTE} /
 * {@link #HOUR}. Choosing a bucket coarser than the event rate silently discards the earlier
 * facts in each bucket.
 *
 * <p>{@link #NONE} is not "finest" — it drops the {@code _period} column entirely and keys rows on
 * the dimensions alone, so the register holds exactly one current row per dimension tuple.
 */
public enum Periodicity {

    /** No period column: one current row per dimension tuple. */
    NONE,

    /** Floored to the second — the finest bucket, for event logs and audit trails. */
    SECOND,

    /** Floored to the minute. */
    MINUTE,

    /** Floored to the hour. */
    HOUR,

    /** Floored to midnight of the day. */
    DAY,

    /** Floored to the first day of the month. */
    MONTH,

    /** Floored to the first day of the calendar quarter. */
    QUARTER,

    /** Floored to January 1st of the year. */
    YEAR
}
