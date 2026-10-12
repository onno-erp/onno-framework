package su.onno.ui;

/** Reusable page content. Configure at startup, then place it with {@link PageBuilder#view}.
 * Entity metadata and access checks remain owned by the underlying list/widget renderer.
 */
@FunctionalInterface
public interface PageView {
    void compose(PageBuilder page);
}
