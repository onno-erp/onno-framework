package su.onno.period;

import java.util.Optional;

/**
 * Decides whether the caller of the current operation may change a closed period. The UI starter
 * registers one that grants the override to signed-in users explicitly granted
 * {@link su.onno.access.CorePermissions#OVERRIDE_PERIOD_CLOSE}; without a policy nobody is exempt
 * and a closed period can only be changed after {@link PeriodCloseService#reopen reopening} it or
 * inside an explicit
 * {@link PeriodCloseService#override(PeriodOverride, java.util.function.Supplier)} block.
 */
@FunctionalInterface
public interface PeriodCloseOverridePolicy {

    /** The override the current caller holds, or empty when the close applies to them. */
    Optional<PeriodOverride> current();

    /**
     * Who is acting in the current operation, for audit entries that are not overrides (an
     * {@linkplain PeriodAdjustment adjustment}); {@code null} when unknown.
     */
    default String actor() {
        return current().map(PeriodOverride::actor).orElse(null);
    }
}
