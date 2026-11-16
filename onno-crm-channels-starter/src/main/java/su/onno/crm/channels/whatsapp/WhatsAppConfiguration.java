package su.onno.crm.channels.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.onno.crm.channels.CrmChannelsProperties;

/**
 * Opt-in WhatsApp client built from {@link CrmChannelsProperties}, which owns its credential-file
 * default. A host bean of type {@link WhatsAppClient} replaces it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onno.crm.channels.whatsapp.enabled", havingValue = "true")
public class WhatsAppConfiguration {
    @Bean
    @ConditionalOnMissingBean
    WhatsAppClient whatsAppClient(CrmChannelsProperties properties, ObjectMapper json) {
        return new WhatsAppClient(properties.getWhatsapp().getTokensFile(), json);
    }
}
