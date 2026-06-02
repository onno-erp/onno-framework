package com.onec.mail;

import com.onec.mail.dispatch.CompositeMailDispatcher;
import com.onec.mail.dispatch.FileMailDispatcher;
import com.onec.mail.dispatch.HttpMailDispatcher;
import com.onec.mail.dispatch.LoggingMailDispatcher;
import com.onec.mail.dispatch.MailDispatcher;
import com.onec.mail.dispatch.SmtpMailDispatcher;
import com.onec.mail.outbox.MailOutbox;
import com.onec.mail.outbox.MailOutboxRelay;
import com.onec.mail.outbox.MailOutboxRelayScheduler;
import com.onec.mail.suppression.MailSuppressionList;
import com.onec.mail.template.MailRenderer;
import com.onec.mail.template.MailScanner;
import com.onec.mail.template.MailTemplateRegistry;
import com.onec.mail.web.MailEventController;
import com.onec.mail.web.MailPreviewController;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.util.List;

@AutoConfiguration(after = MailSenderAutoConfiguration.class)
@ConditionalOnClass(JavaMailSender.class)
@ConditionalOnProperty(prefix = "onec.mail", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MailProperties.class)
public class OneCMailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MailTemplateRegistry mailTemplateRegistry(ApplicationContext context, MailProperties properties) {
        MailTemplateRegistry registry = new MailTemplateRegistry();
        List<String> packages = properties.getBasePackages().isEmpty()
                ? AutoConfigurationPackages.get(context)
                : properties.getBasePackages();
        new MailScanner().scan(packages).forEach(registry::register);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public MailRenderer mailRenderer(ResourceLoader resourceLoader, MailProperties properties) {
        return new MailRenderer(resourceLoader, properties);
    }

    // --- Provider dispatchers (coexist as candidates; the active one is chosen by onec.mail.provider) ---

    @Bean
    @ConditionalOnBean(JavaMailSender.class)
    @ConditionalOnMissingBean(SmtpMailDispatcher.class)
    public SmtpMailDispatcher smtpMailDispatcher(JavaMailSender javaMailSender, MailProperties properties) {
        return new SmtpMailDispatcher(javaMailSender, properties);
    }

    @Bean
    @ConditionalOnMissingBean(LoggingMailDispatcher.class)
    public LoggingMailDispatcher loggingMailDispatcher() {
        return new LoggingMailDispatcher();
    }

    @Bean
    @ConditionalOnMissingBean(FileMailDispatcher.class)
    public FileMailDispatcher fileMailDispatcher(MailProperties properties) {
        return new FileMailDispatcher(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "onec.mail", name = "provider", havingValue = "failover")
    @ConditionalOnMissingBean(CompositeMailDispatcher.class)
    public CompositeMailDispatcher compositeMailDispatcher(ObjectProvider<MailDispatcher> dispatcherProvider,
                                                           MailProperties properties) {
        List<MailDispatcher> candidates = dispatcherProvider.stream()
                .filter(d -> !"failover".equalsIgnoreCase(d.name()))
                .toList();
        List<String> names = properties.getFailover().getProviders();
        if (names.isEmpty()) {
            throw new IllegalStateException("onec.mail.provider=failover requires onec.mail.failover.providers");
        }
        List<MailDispatcher> chain = names.stream()
                .map(name -> candidates.stream()
                        .filter(d -> name.equalsIgnoreCase(d.name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "failover provider '" + name + "' not found (available: "
                                        + candidates.stream().map(MailDispatcher::name).toList() + ")")))
                .toList();
        return new CompositeMailDispatcher(chain);
    }

    // --- Outbox, suppression, service, relay ---

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public MailOutbox mailOutbox(DataSource dataSource) {
        MailOutbox outbox = new MailOutbox(Jdbi.create(dataSource));
        outbox.initSchema();
        return outbox;
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public MailSuppressionList mailSuppressionList(DataSource dataSource) {
        MailSuppressionList suppression = new MailSuppressionList(Jdbi.create(dataSource));
        suppression.initSchema();
        return suppression;
    }

    @Bean
    @ConditionalOnBean(MailDispatcher.class)
    @ConditionalOnMissingBean
    public MailService mailService(ObjectProvider<MailDispatcher> dispatcherProvider,
                                   MailTemplateRegistry templates,
                                   MailRenderer renderer,
                                   MailProperties properties,
                                   ObjectProvider<MailOutbox> outboxProvider,
                                   ObjectProvider<MailSuppressionList> suppressionProvider,
                                   ObjectMapper objectMapper) {
        MailDispatcher dispatcher = selectDispatcher(dispatcherProvider, properties.getProvider());
        return new MailService(dispatcher, templates, renderer, properties,
                outboxProvider.getIfAvailable(), suppressionProvider.getIfAvailable(), objectMapper);
    }

    @Bean
    @ConditionalOnBean({MailOutbox.class, MailDispatcher.class})
    @ConditionalOnMissingBean
    public MailOutboxRelay mailOutboxRelay(MailOutbox outbox,
                                           ObjectProvider<MailDispatcher> dispatcherProvider,
                                           ObjectMapper objectMapper,
                                           MailProperties properties) {
        return new MailOutboxRelay(outbox, selectDispatcher(dispatcherProvider, properties.getProvider()),
                objectMapper, properties);
    }

    private MailDispatcher selectDispatcher(ObjectProvider<MailDispatcher> provider, String name) {
        List<MailDispatcher> all = provider.stream().toList();
        if (all.isEmpty()) {
            throw new IllegalStateException("No MailDispatcher beans available");
        }
        if (name == null || name.isBlank()) {
            return all.get(0);
        }
        return all.stream()
                .filter(d -> name.equalsIgnoreCase(d.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No MailDispatcher named '" + name + "' (available: "
                                + all.stream().map(MailDispatcher::name).toList() + ")"));
    }

    /** Scheduled relay that drains the outbox. Requires an outbox and {@code onec.mail.relay.enabled=true}. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBean(MailOutboxRelay.class)
    @ConditionalOnProperty(prefix = "onec.mail.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RelaySchedulingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MailOutboxRelayScheduler mailOutboxRelayScheduler(MailOutboxRelay relay) {
            return new MailOutboxRelayScheduler(relay);
        }
    }

    /** Universal HTTP provider. Only wired when spring-web (RestClient) is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    static class HttpDispatcherConfiguration {

        @Bean
        @ConditionalOnBean(RestClient.Builder.class)
        @ConditionalOnMissingBean(HttpMailDispatcher.class)
        public HttpMailDispatcher httpMailDispatcher(RestClient.Builder restClientBuilder,
                                                     ResourceLoader resourceLoader,
                                                     ObjectMapper objectMapper,
                                                     MailProperties properties) {
            return new HttpMailDispatcher(restClientBuilder, resourceLoader, objectMapper, properties);
        }
    }

    /** Dev preview + delivery-event webhook endpoints. Only in servlet web apps, each gated by its own flag. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebEndpointsConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "onec.mail.preview", name = "enabled", havingValue = "true")
        @ConditionalOnMissingBean
        public MailPreviewController mailPreviewController(MailTemplateRegistry registry, MailRenderer renderer) {
            return new MailPreviewController(registry, renderer);
        }

        @Bean
        @ConditionalOnBean(MailSuppressionList.class)
        @ConditionalOnProperty(prefix = "onec.mail.webhook", name = "enabled", havingValue = "true")
        @ConditionalOnMissingBean
        public MailEventController mailEventController(MailSuppressionList suppressionList) {
            return new MailEventController(suppressionList);
        }
    }
}
