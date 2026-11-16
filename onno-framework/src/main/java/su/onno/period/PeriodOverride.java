package su.onno.period;

/**
 * Permission to change documents inside a closed period. Every use is written to the audit trail.
 *
 * @param actor  who is overriding (a login, a job name)
 * @param reason why, recorded with each audited override
 */
public record PeriodOverride(String actor, String reason) {

    public PeriodOverride {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("A period override needs an actor");
        }
    }
}
