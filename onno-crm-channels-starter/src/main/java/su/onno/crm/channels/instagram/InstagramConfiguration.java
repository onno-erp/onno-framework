package su.onno.crm.channels.instagram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.onno.crm.channels.CrmChannelsProperties;

/**
 * Opt-in Instagram client built from {@link CrmChannelsProperties}, which owns its credential-file
 * default. A host bean of type {@link InstagramClient} replaces it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onno.crm.channels.instagram.enabled", havingValue = "true")
public class InstagramConfiguration {
    @Bean
    @ConditionalOnMissingBean
    InstagramClient instagramClient(CrmChannelsProperties properties, ObjectMapper json) {
        return new InstagramClient(properties.getInstagram().getTokensFile(), json);
    }
}
