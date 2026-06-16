package com.onec.auth;

import com.onec.auth.magic.MagicLinkController;
import com.onec.auth.magic.MagicLinkSender;
import com.onec.auth.magic.MagicLinkService;
import com.onec.auth.magic.MagicLinkTokenStore;
import com.onec.auth.spi.AuthMethodsProvider;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class OnecAuthMagicLinkConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withPropertyValues("onec.auth.session.remember-me.allow-ephemeral-key=true")
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OnecAuthAutoConfiguration.class));

    @Test
    void magicLinkIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MagicLinkController.class);
            assertThat(context).doesNotHaveBean(MagicLinkService.class);
            assertThat(context.getBean(AuthMethodsProvider.class).authMethods().magicLinkEnabled())
                    .isFalse();
        });
    }

    @Test
    void enablingWithDataSourceAndSenderWiresEndpointsAndAdvertisesIt() {
        runner.withUserConfiguration(DataSourceConfig.class, SenderConfig.class)
                .withPropertyValues("onec.auth.magic-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MagicLinkController.class);
                    assertThat(context).hasSingleBean(MagicLinkService.class);
                    assertThat(context).hasSingleBean(MagicLinkTokenStore.class);
                    assertThat(context.getBean(AuthMethodsProvider.class).authMethods().magicLinkEnabled())
                            .isTrue();
                });
    }

    @Test
    void enablingWithoutADataSourceFailsFastWithGuidance() {
        runner.withUserConfiguration(SenderConfig.class)
                .withPropertyValues("onec.auth.magic-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasMessageContaining("DataSource");
                });
    }

    @Test
    void enablingWithoutASenderFailsFastWithGuidance() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("onec.auth.magic-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasMessageContaining("MagicLinkSender");
                });
    }

    @Test
    void notWiredInOidcModeEvenWhenEnabled() {
        runner.withUserConfiguration(DataSourceConfig.class, SenderConfig.class, ClientRegistrationConfig.class)
                .withPropertyValues("onec.auth.mode=oidc", "onec.auth.magic-link.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // OIDC delegates sign-in to the IdP, so the in-memory-only magic-link flow stays off.
                    assertThat(context).doesNotHaveBean(MagicLinkController.class);
                    assertThat(context.getBean(AuthMethodsProvider.class).authMethods().magicLinkEnabled())
                            .isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:ml-cfg-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            return ds;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SenderConfig {
        @Bean
        MagicLinkSender magicLinkSender() {
            return (email, link, validity) -> {
                // no-op test sender
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientRegistrationConfig {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration keycloak = ClientRegistration.withRegistrationId("keycloak")
                    .clientId("app")
                    .clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri("https://keycloak.local/auth")
                    .tokenUri("https://keycloak.local/token")
                    .jwkSetUri("https://keycloak.local/certs")
                    .userNameAttributeName("preferred_username")
                    .build();
            return new InMemoryClientRegistrationRepository(keycloak);
        }
    }
}
