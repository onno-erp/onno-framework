package su.onno.crm.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.onno.crm.channels.gmail.GmailBridge;
import su.onno.crm.channels.gmail.GmailClient;
import su.onno.crm.channels.instagram.InstagramBridge;
import su.onno.crm.channels.instagram.InstagramClient;
import su.onno.crm.channels.whatsapp.WhatsAppBridge;
import su.onno.crm.channels.whatsapp.WhatsAppClient;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.CrmChannelAccess;
import su.onno.crm.service.CrmConnectionAccounts;
import su.onno.crm.service.CrmCustomerBinding;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.crm.service.CrmMessageTransport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChannelsAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OnnoCrmChannelsAutoConfiguration.class));

    @Test void noHostBindingInstallsNoChannelConfiguration() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CrmChannelsProperties.class);
            assertThat(context).doesNotHaveBean(CrmMessageTransport.class);
        });
    }

    @Test void bindingCustomersDoesNotEnableAnyProvider() {
        runner.withBean(CrmCustomerBinding.class, () -> mock(CrmCustomerBinding.class)).run(context -> {
            assertThat(context).hasSingleBean(CrmChannelsProperties.class);
            assertThat(context).doesNotHaveBean(CrmMessageTransport.class);
        });
    }

    /** The auto-configuration itself imports each client configuration; a test of the configurations alone would not notice one dropped. */
    @Test void enabledProvidersGetTheirClientsThroughTheAutoConfiguration() {
        runner.withBean(CrmCustomerBinding.class, () -> mock(CrmCustomerBinding.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(HostBridges.class)
                .withBean(CrmChannelAccess.class, () -> mock(CrmChannelAccess.class))
                .withBean(CrmConnectionAccounts.class, () -> mock(CrmConnectionAccounts.class))
                .withBean(CrmInboxWorkspaceService.class, () -> mock(CrmInboxWorkspaceService.class))
                .withBean(ConversationRepository.class, () -> mock(ConversationRepository.class))
                .withPropertyValues("onno.crm.channels.gmail.enabled=true", "onno.crm.channels.instagram.enabled=true",
                        "onno.crm.channels.whatsapp.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GmailClient.class);
                    assertThat(context).hasSingleBean(InstagramClient.class);
                    assertThat(context).hasSingleBean(WhatsAppClient.class);
                });
    }

    /** Host bridges replace the bundled ones, whose JDBC and repository wiring is not under test here. */
    @Configuration(proxyBeanMethods = false)
    static class HostBridges {
        @Bean GmailBridge gmailBridge() { return mock(GmailBridge.class); }
        @Bean InstagramBridge instagramBridge() { return mock(InstagramBridge.class); }
        @Bean WhatsAppBridge whatsAppBridge() { return mock(WhatsAppBridge.class); }
    }
}
