package su.onno.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comments are on for every known catalog/document ({@link DefaultListViews#commentsEnabled(Class)});
 * the global {@code onno.comments.enabled} switch is the only gate, and the client decides where to
 * show the thread.
 */
class DefaultListViewsCommentsTest {

    private static final class AnyEntity {}

    private final DefaultListViews resolver = new DefaultListViews(null);

    @Test
    void enabledForEveryKnownEntity() {
        assertThat(resolver.commentsEnabled(AnyEntity.class)).isTrue();
        assertThat(resolver.commentsEnabled(null)).isFalse();
    }
}
