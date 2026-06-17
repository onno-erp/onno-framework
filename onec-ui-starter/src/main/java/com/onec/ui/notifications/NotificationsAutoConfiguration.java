package com.onec.ui.notifications;

import com.onec.metadata.MetadataRegistry;
import com.onec.ui.CurrentUserResolver;
import com.onec.ui.UiAccessService;
import com.onec.ui.UiAutoConfiguration;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code /api/notifications} inbox endpoint, its {@link NotificationStore} store, the
 * {@link NotificationService} producer API, the {@code onec.notifications.*} configuration, and the
 * two built-in producers (mention + posting bridges). Gated on a servlet web app and
 * {@code onec.notifications.enabled} (default true), and runs after {@link UiAutoConfiguration} so the
 * {@link UiAccessService} and {@link CurrentUserResolver} it builds on are already present. Storage is
 * the framework-owned {@code onec_notifications} table the store creates on startup — there is no app
 * metadata to model.
 */
@AutoConfiguration(after = UiAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(MetadataRegistry.class)
@EnableConfigurationProperties(NotificationProperties.class)
@ConditionalOnProperty(prefix = "onec.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationsAutoConfiguration {

    @Bean
    public NotificationStore notificationStore(Jdbi jdbi) {
        return new NotificationStore(jdbi);
    }

    @Bean
    public NotificationService notificationService(NotificationStore store, CurrentUserResolver currentUserResolver,
                                                   UiAccessService access, ApplicationEventPublisher events,
                                                   NotificationProperties properties) {
        return new NotificationService(store, currentUserResolver, access, events, properties);
    }

    @Bean
    public NotificationController notificationController(NotificationService service) {
        return new NotificationController(service);
    }

    @Bean
    public MentionNotificationListener mentionNotificationListener(NotificationService service,
                                                                   NotificationProperties properties) {
        return new MentionNotificationListener(service, properties);
    }

    @Bean
    public PostNotificationListener postNotificationListener(NotificationService service,
                                                             NotificationProperties properties) {
        return new PostNotificationListener(service, properties);
    }
}
