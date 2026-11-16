package su.onno.crm.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import su.onno.crm.channels.gmail.GmailClient;
import su.onno.crm.channels.gmail.GmailConfiguration;
import su.onno.crm.channels.instagram.InstagramClient;
import su.onno.crm.channels.instagram.InstagramConfiguration;
import su.onno.crm.channels.whatsapp.WhatsAppClient;
import su.onno.crm.channels.whatsapp.WhatsAppConfiguration;
import static org.assertj.core.api.Assertions.*;

/** Provider clients read their files from the typed {@link CrmChannelsProperties}, not duplicated defaults. */
class ChannelClientConfigurationTest {
    @TempDir Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, GmailConfiguration.class, InstagramConfiguration.class,
                    WhatsAppConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void disabledProvidersCreateNoClients() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(GmailClient.class);
            assertThat(context).doesNotHaveBean(InstagramClient.class);
            assertThat(context).doesNotHaveBean(WhatsAppClient.class);
        });
    }

    @Test void enabledClientsUseTheConfiguredFiles() throws Exception {
        Path gmail = Files.writeString(directory.resolve("gmail-client.json"), "{}");
        Path instagram = Files.writeString(directory.resolve("instagram-tokens.json"), "{}");
        Path whatsapp = Files.writeString(directory.resolve("whatsapp-tokens.json"), "{}");
        enabled(gmail, instagram, whatsapp).run(context -> {
            assertThat(context.getBean(GmailClient.class).configured()).isTrue();
            assertThat(context.getBean(InstagramClient.class).configured()).isTrue();
            assertThat(context.getBean(WhatsAppClient.class).configured()).isTrue();
        });
        Path missing = directory.resolve("missing.json");
        enabled(missing, missing, missing).run(context -> {
            assertThat(context.getBean(GmailClient.class).configured()).isFalse();
            assertThat(context.getBean(InstagramClient.class).configured()).isFalse();
            assertThat(context.getBean(WhatsAppClient.class).configured()).isFalse();
        });
    }

    /** Every Gmail setting reaches the client: a missing tokens file must not borrow the client file. */
    @Test void gmailClientUsesEveryConfiguredSetting() throws Exception {
        Path client = Files.writeString(directory.resolve("gmail-client.json"),
                "{\"web\":{\"client_id\":\"id\",\"client_secret\":\"secret\"}}");
        Path tokens = Files.writeString(directory.resolve("gmail-tokens.json"), "{}");
        String redirect = "https://crm.example/api/crm/gmail/callback";
        gmail(client, tokens, redirect).run(context -> {
            GmailClient gmail = context.getBean(GmailClient.class);
            assertThat(gmail.configured()).isTrue();
            assertThat(gmail.authorized()).isTrue();
            assertThat(gmail.authorizationUrl("state", "challenge"))
                    .contains("redirect_uri=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8));
        });
        gmail(client, directory.resolve("missing.json"), redirect).run(context ->
                assertThat(context.getBean(GmailClient.class).authorized()).isFalse());
    }

    @Test void hostClientReplacesTheBundledOne() {
        var host = new GmailClient(new ObjectMapper(), "client.json", "tokens.json", "https://crm.example/callback");
        runner.withBean(GmailClient.class, () -> host)
                .withPropertyValues("onno.crm.channels.gmail.enabled=true")
                .run(context -> assertThat(context).getBean(GmailClient.class).isSameAs(host));
    }

    @Test void hostClientsNamedLikeTheBundledOnesReplaceThem() {
        var json = new ObjectMapper();
        var gmail = new GmailClient(json, "client.json", "tokens.json", "https://crm.example/callback");
        var instagram = new InstagramClient("instagram-tokens.json", json);
        var whatsapp = new WhatsAppClient("whatsapp-tokens.json", json);
        runner.withBean("gmailClient", GmailClient.class, () -> gmail)
                .withBean("instagramClient", InstagramClient.class, () -> instagram)
                .withBean("whatsAppClient", WhatsAppClient.class, () -> whatsapp)
                .withPropertyValues("onno.crm.channels.gmail.enabled=true",
                        "onno.crm.channels.instagram.enabled=true", "onno.crm.channels.whatsapp.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(GmailClient.class).isSameAs(gmail);
                    assertThat(context).getBean(InstagramClient.class).isSameAs(instagram);
                    assertThat(context).getBean(WhatsAppClient.class).isSameAs(whatsapp);
                });
    }

    private ApplicationContextRunner enabled(Path gmailClientFile, Path instagramTokens, Path whatsappTokens) {
        return runner.withPropertyValues(
                "onno.crm.channels.gmail.enabled=true",
                "onno.crm.channels.gmail.client-file=" + gmailClientFile,
                "onno.crm.channels.instagram.enabled=true",
                "onno.crm.channels.instagram.tokens-file=" + instagramTokens,
                "onno.crm.channels.whatsapp.enabled=true",
                "onno.crm.channels.whatsapp.tokens-file=" + whatsappTokens);
    }

    private ApplicationContextRunner gmail(Path clientFile, Path tokensFile, String redirectUri) {
        return runner.withPropertyValues(
                "onno.crm.channels.gmail.enabled=true",
                "onno.crm.channels.gmail.client-file=" + clientFile,
                "onno.crm.channels.gmail.tokens-file=" + tokensFile,
                "onno.crm.channels.gmail.redirect-uri=" + redirectUri);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CrmChannelsProperties.class)
    static class Properties {
    }
}
