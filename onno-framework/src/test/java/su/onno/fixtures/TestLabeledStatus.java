package su.onno.fixtures;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * Fixture exercising {@code @Enumeration.title} and per-constant {@code @EnumLabel}, including a
 * deliberately unlabelled constant ({@code COMPLETED}) to cover the name-fallback path.
 */
@Enumeration(name = "LabeledStatuses", title = "Статусы заказов")
public enum TestLabeledStatus {
    @EnumLabel("Новый") NEW,
    @EnumLabel("В работе") IN_PROGRESS,
    COMPLETED
}
