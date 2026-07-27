package su.onno.ui.collaboration;

import su.onno.cluster.ClusterEventBus;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.EntityDetailUiContributor;
import su.onno.ui.FieldHintResolver;
import su.onno.ui.ShellUiContributor;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiAutoConfiguration;
import su.onno.ui.UiEventPublisher;
import su.onno.ui.UiViewResolver;
import su.onno.ui.UserAvatarResolver;
import su.onno.ui.comments.CommentProperties;
import su.onno.ui.comments.CommentService;
import su.onno.ui.comments.CommentsAutoConfiguration;
import su.onno.ui.notifications.NotificationProperties;
import su.onno.ui.notifications.NotificationsAutoConfiguration;
import su.onno.ui.presence.PresenceController;
import su.onno.ui.presence.PresenceRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {
        UiAutoConfiguration.class,
        CommentsAutoConfiguration.class,
        NotificationsAutoConfiguration.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({MetadataRegistry.class, FieldHintResolver.class, UiEventPublisher.class})
@ConditionalOnProperty(prefix = "onno.collaboration", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CollaborationProperties.class)
public class CollaborationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PresenceRegistry presenceRegistry(ClusterEventBus clusterEventBus,
                                             UiEventPublisher publisher) {
        return new PresenceRegistry(clusterEventBus, publisher);
    }

    @Bean
    public PresenceController presenceController(PresenceRegistry presenceRegistry,
                                                 UiAccessService access,
                                                 CurrentUserResolver currentUserResolver,
                                                 UserAvatarResolver avatars) {
        return new PresenceController(presenceRegistry, access, currentUserResolver, avatars);
    }

    @Bean
    @ConditionalOnBean(CommentService.class)
    public EntityDetailUiContributor commentsDetailContributor(
            CommentProperties properties, UiViewResolver viewResolver) {
        return new CommentsDetailContributor(properties, viewResolver);
    }

    @Bean
    public ShellUiContributor collaborationShellContributor(
            ObjectProvider<NotificationProperties> notifications) {
        return new CollaborationShellContributor(notifications.getIfAvailable() != null);
    }
}
