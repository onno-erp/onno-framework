package su.onno.crm.channels.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.onno.crm.channels.CrmChannelsProperties;

/**
 * Opt-in Gmail client built from {@link CrmChannelsProperties}, which owns its file and callback
 * defaults. A host bean of type {@link GmailClient} replaces it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onno.crm.channels.gmail.enabled", havingValue = "true")
public class GmailConfiguration {
    @Bean
    @ConditionalOnMissingBean
    GmailClient gmailClient(CrmChannelsProperties properties, ObjectMapper json) {
        var gmail = properties.getGmail();
        return new GmailClient(json, gmail.getClientFile(), gmail.getTokensFile(), gmail.getRedirectUri());
    }
}
