package su.onno.web.notifications;

import su.onno.cluster.ClusterEventBus;
import su.onno.metadata.MetadataRegistry;
import su.onno.service.CurrentUserResolver;
import su.onno.validation.CheckRegistry;
import su.onno.web.UiAutoConfiguration;
import su.onno.web.EventPublisher;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code /api/notifications} per-user notification timeline, its {@link NotificationStore},
 * the {@link NotificationService} delivery hub, and the {@code onno.notifications.*} configuration.
 * Gated on a servlet web app and {@code onno.notifications.enabled} (default true), and runs after
 * {@link UiAutoConfiguration} so the {@link EventPublisher} (SSE fan-out), {@link CurrentUserResolver}
 * (recipient identity), and {@link ClusterEventBus} (cross-node relay) it builds on already exist — the
 * same layering the comments feature uses. Storage is the framework-owned {@code onno_notifications}
 * table the store creates on startup; there is no app metadata to model.
 *
 * <p>The built-in producers ({@link MentionNotificationSource}, {@link AssignmentNotificationSource},
 * {@link ReplyNotificationSource}) are each gated by their own sub-flag so an app can keep the panel
 * while turning a source off.
 */
@AutoConfiguration(after = UiAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({MetadataRegistry.class, CheckRegistry.class, EventPublisher.class})
@EnableConfigurationProperties(NotificationProperties.class)
@ConditionalOnProperty(prefix = "onno.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationsAutoConfiguration {

    @Bean
    public NotificationStore notificationStore(Jdbi jdbi) {
        return new NotificationStore(jdbi);
    }

    @Bean
    public NotificationService notificationService(NotificationStore store, EventPublisher publisher,
                                                   ClusterEventBus clusterEventBus,
                                                   NotificationProperties properties) {
        return new NotificationService(store, publisher, clusterEventBus, properties);
    }

    @Bean
    public NotificationController notificationController(NotificationService notificationService,
                                                        CurrentUserResolver currentUserResolver,
                                                        su.onno.service.RecordAccess recordAccess) {
        return new NotificationController(notificationService, currentUserResolver, recordAccess);
    }

    @Bean
    @ConditionalOnProperty(prefix = "onno.notifications.mentions", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MentionNotificationSource mentionNotificationSource(NotificationService notificationService,
                                                              su.onno.service.IdentityBinding identityBinding,
                                                              MetadataRegistry registry) {
        return new MentionNotificationSource(notificationService, identityBinding, registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "onno.notifications.assignments", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AssignmentNotificationSource assignmentNotificationSource(NotificationService notificationService,
                                                                    su.onno.service.IdentityBinding identityBinding,
                                                                    MetadataRegistry registry, Jdbi jdbi) {
        return new AssignmentNotificationSource(notificationService, identityBinding, registry, jdbi);
    }

    @Bean
    @ConditionalOnProperty(prefix = "onno.notifications.replies", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ReplyNotificationSource replyNotificationSource(NotificationService notificationService) {
        return new ReplyNotificationSource(notificationService);
    }
}
