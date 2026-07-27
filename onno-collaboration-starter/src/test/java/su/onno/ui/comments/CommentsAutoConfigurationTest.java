package su.onno.ui.comments;

import su.onno.metadata.MetadataRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gating contract for {@link CommentsAutoConfiguration}. Comments are a UI-surface feature whose
 * beans hard-depend on the beans {@code UiAutoConfiguration} only creates when {@code onno.ui
 * .enabled} is true. With the UI disabled but comments left at their default-on flag, the comment
 * beans must skip rather than crash the context — the regression reported in #143.
 */
class CommentsAutoConfigurationTest {

    // A MetadataRegistry is always present (OnnoAutoConfiguration builds it regardless of the UI
    // flag) — that is exactly the case that used to let CommentsAutoConfiguration activate against
    // absent UI beans.
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MetadataConfig.class)
            .withConfiguration(AutoConfigurations.of(CommentsAutoConfiguration.class));

    @Test
    void commentsSkipWhenUiBeansAreAbsentSoTheContextStillStarts() {
        // No FieldHintResolver/UiAccessService/etc. on the classpath context — i.e. onno.ui.enabled
        // was false and UiAutoConfiguration contributed nothing.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CommentController.class);
            assertThat(context).doesNotHaveBean(CommentService.class);
            assertThat(context).doesNotHaveBean(MentionController.class);
        });
    }

    @Test
    void commentsStayOffWhenExplicitlyDisabledEvenIfUiBeansExist() {
        runner.withPropertyValues("onno.comments.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CommentController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class MetadataConfig {
        @Bean
        MetadataRegistry metadataRegistry() {
            return new MetadataRegistry();
        }
    }
}
