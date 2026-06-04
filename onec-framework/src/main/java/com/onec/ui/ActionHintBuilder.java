package com.onec.ui;

/**
 * Per-action placement builder for a document's detail-header actions
 * ({@code post} / {@code unpost} / {@code edit} / {@code delete}): show it as a
 * primary button, tuck it into the overflow (⋯) menu, or hide it entirely.
 *
 * <p>Obtained from {@link EntityConfigBuilder#action(String)}; the terminal
 * methods return the parent so calls chain:
 * <pre>{@code
 * f.action("delete").inMenu()
 *  .action("post").primary()
 *  .action("unpost").hidden();
 * }</pre>
 */
public class ActionHintBuilder {

    private final EntityConfigBuilder parent;
    private final String name;

    ActionHintBuilder(EntityConfigBuilder parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    /** Show this action as a primary button in the detail header. */
    public EntityConfigBuilder primary() {
        parent.putAction(name, "primary");
        return parent;
    }

    /** Tuck this action into the overflow (⋯) menu. */
    public EntityConfigBuilder inMenu() {
        parent.putAction(name, "menu");
        return parent;
    }

    /** Hide this action from the UI entirely (it stays available via REST). */
    public EntityConfigBuilder hidden() {
        parent.putAction(name, "hidden");
        return parent;
    }
}
