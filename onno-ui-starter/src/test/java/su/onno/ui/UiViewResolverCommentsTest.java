package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-entity, opt-in comments: {@link UiViewResolver#commentsEnabled(Class)} is true only when one of
 * an entity's views opts in via {@link EntityView#comments()}, and the opt-in is resolved at the
 * entity level (any profile view counts). This is what gates the comment panel and the
 * {@code /api/comments} endpoint.
 */
class UiViewResolverCommentsTest {

    private static final class OptedIn {}
    private static final class OptedOut {}
    private static final class ProfileOnly {}

    private static EntityView view(Class<?> entity, String profile, boolean comments) {
        return new EntityView() {
            @Override public Class<?> entity() { return entity; }
            @Override public String profile() { return profile; }
            @Override public boolean comments() { return comments; }
        };
    }

    @Test
    void enabledOnlyWhenAViewOptsIn() {
        UiViewResolver resolver = new UiViewResolver(null, List.of(
                view(OptedIn.class, null, true),
                view(OptedOut.class, null, false)));

        assertThat(resolver.commentsEnabled(OptedIn.class)).isTrue();
        assertThat(resolver.commentsEnabled(OptedOut.class)).as("default is off").isFalse();
        assertThat(resolver.commentsEnabled(Object.class)).as("no view → off").isFalse();
        assertThat(resolver.commentsEnabled(null)).isFalse();
    }

    @Test
    void anyProfileViewOptingInEnablesItAtEntityLevel() {
        // The default view is off but a profile-specific view turns it on — still enabled.
        UiViewResolver resolver = new UiViewResolver(null, List.of(
                view(ProfileOnly.class, null, false),
                view(ProfileOnly.class, "cleaning", true)));

        assertThat(resolver.commentsEnabled(ProfileOnly.class)).isTrue();
    }
}
