package su.onno.period;

import java.util.Optional;

/**
 * Decides whether the caller of the current operation may change a closed period. The UI starter
 * registers one that grants the override to signed-in users holding a role listed in
 * {@code onno.period-close.override-roles}; without a policy nobody is exempt and a closed period
 * can only be changed after {@link PeriodCloseService#reopen reopening} it or inside an explicit
 * {@link PeriodCloseService#override(PeriodOverride, java.util.function.Supplier)} block.
 */
@FunctionalInterface
public interface PeriodCloseOverridePolicy {

    /** The override the current caller holds, or empty when the close applies to them. */
    Optional<PeriodOverride> current();
}
