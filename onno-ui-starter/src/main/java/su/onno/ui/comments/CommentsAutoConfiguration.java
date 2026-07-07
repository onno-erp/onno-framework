package su.onno.ui.comments;

import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.FieldHintResolver;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiAutoConfiguration;
import su.onno.ui.UiViewResolver;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code /api/comments} discussion-thread endpoint, its {@link CommentService} store, and
 * the {@code onno.comments.*} configuration. Gated on a servlet web app and {@code onno.comments
 * .enabled} (default true), and runs after {@link UiAutoConfiguration} so the {@link UiAccessService}
 * and {@link CurrentUserResolver} it builds on are already present. Comments are a UI-surface
 * feature whose beans hard-depend on the UI beans, so it also requires {@link FieldHintResolver} —
 * one of the beans {@code UiAutoConfiguration} only creates when {@code onno.ui.enabled} is true.
 * That keeps the context from failing to start when the UI is disabled but comments are left at
 * their default-on flag. Storage is the framework-owned {@code onno_comments} table the service
 * creates on startup — there is no app metadata to model.
 */
@AutoConfiguration(after = UiAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({MetadataRegistry.class, FieldHintResolver.class})
@EnableConfigurationProperties(CommentProperties.class)
@ConditionalOnProperty(prefix = "onno.comments", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommentsAutoConfiguration {

    @Bean
    public CommentService commentService(Jdbi jdbi) {
        return new CommentService(jdbi);
    }

    @Bean
    public MentionResolver mentionResolver(MetadataRegistry registry, UiAccessService access, Jdbi jdbi) {
        return new MentionResolver(registry, access, jdbi);
    }

    @Bean
    public MentionController mentionController(MetadataRegistry registry, CatalogQueryService catalogQuery,
                                              DocumentQueryService documentQuery, UiAccessService access,
                                              CommentProperties properties, su.onno.ui.UiLayout uiLayout,
                                              MentionResolver mentionResolver) {
        return new MentionController(registry, catalogQuery, documentQuery, access, properties, uiLayout,
                mentionResolver);
    }

    @Bean
    public CommentController commentController(CommentService commentService, UiAccessService access,
                                               CurrentUserResolver currentUserResolver,
                                               CommentAuthorAvatars authorAvatars,
                                               CommentProperties properties, UiViewResolver viewResolver,
                                               CatalogQueryService catalogQuery,
                                               DocumentQueryService documentQuery,
                                               MentionResolver mentionResolver,
                                               ApplicationEventPublisher events) {
        return new CommentController(commentService, access, currentUserResolver, authorAvatars,
                properties, viewResolver, catalogQuery, documentQuery, mentionResolver, events);
    }
}
