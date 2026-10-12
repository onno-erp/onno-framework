package su.onno.ui;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reusable entity list placement; {@link EntityView} still supplies columns, fields and actions.
 *
 * <p>Configure once at startup with {@link #defaults}, e.g. {@code .defaults(d -> d.fill().sort("date", true))}.
 * Each call configures a private draft and keeps a snapshot of it, so a {@link PageBuilder.ListDefaults}
 * reference retained by the callback cannot change the view afterwards. Each placement copies that
 * snapshot into a fresh page component; the first placement freezes the view, so later
 * {@link #defaults} calls throw {@link IllegalStateException}.
 */
public final class ListView<E> implements PageView {
    private final Class<E> entity;
    private PageBuilder.ListDefaults defaults = new PageBuilder.ListDefaults();
    private boolean frozen;

    public ListView(Class<E> entity) { this.entity = Objects.requireNonNull(entity, "entity"); }

    /** Adds list defaults (fill, base filter, default filters, grouping, sort) on top of earlier calls. */
    public synchronized ListView<E> defaults(Consumer<PageBuilder.ListDefaults> configure) {
        Objects.requireNonNull(configure, "configure");
        if (frozen) {
            throw new IllegalStateException("ListView is frozen after its first placement; configure it before composing pages");
        }
        PageBuilder.ListDefaults draft = copyOf(defaults);
        configure.accept(draft);
        // The draft escaped to the callback; keep our own copy so later writes to it are not observed.
        defaults = copyOf(draft);
        return this;
    }

    @Override
    public void compose(PageBuilder page) {
        PageBuilder.ListDefaults snapshot;
        synchronized (this) {
            frozen = true;
            snapshot = defaults;
        }
        // Reuse the same component contract as PageBuilder.list, including server-side resolution.
        // Copying (rather than replaying application callbacks) keeps every placement identical.
        page.list(entity, target -> target.copyFrom(snapshot));
    }

    private static PageBuilder.ListDefaults copyOf(PageBuilder.ListDefaults source) {
        PageBuilder.ListDefaults copy = new PageBuilder.ListDefaults();
        copy.copyFrom(source);
        return copy;
    }
}
