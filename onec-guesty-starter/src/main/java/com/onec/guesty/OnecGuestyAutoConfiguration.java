package com.onec.guesty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the Guesty integration when {@code onec.guesty.enabled=true} and {@code spring-web} is on the
 * classpath. The token manager and client share a {@link RestClient.Builder} (the application's, if it
 * exposes one) configured with the timeout from {@link GuestyProperties}.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = "onec.guesty", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GuestyProperties.class)
public class OnecGuestyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GuestyTokenManager guestyTokenManager(GuestyProperties properties,
                                                 ObjectProvider<RestClient.Builder> builderProvider,
                                                 ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new GuestyTokenManager(properties, restClientBuilder(builderProvider, properties),
                resolveObjectMapper(objectMapperProvider));
    }

    /**
     * The application's {@link ObjectMapper} when it exposes exactly one, otherwise a private mapper.
     * We must NOT define our own {@code ObjectMapper} bean here: a bean of type {@code ObjectMapper}
     * that injects {@code ObjectProvider<ObjectMapper>} resolves to itself when the app has no other
     * mapper (a plain web app may not), and the context fails to start with a self-referencing cycle.
     * Resolving lazily inside the consumer — and using {@code getIfUnique()} so multiple candidates
     * fall back rather than throw — sidesteps that entirely.
     */
    private ObjectMapper resolveObjectMapper(ObjectProvider<ObjectMapper> provider) {
        ObjectMapper shared = provider.getIfUnique();
        if (shared != null) {
            return shared;
        }
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    @ConditionalOnMissingBean
    public GuestyClient guestyClient(GuestyProperties properties,
                                     ObjectProvider<RestClient.Builder> builderProvider,
                                     GuestyTokenManager tokenManager) {
        return new DefaultGuestyClient(restClientBuilder(builderProvider, properties), tokenManager, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public GuestyService guestyService(GuestyClient client) {
        return new GuestyService(client);
    }

    private RestClient.Builder restClientBuilder(ObjectProvider<RestClient.Builder> provider,
                                                 GuestyProperties properties) {
        RestClient.Builder builder = provider.getIfAvailable(RestClient::builder).clone();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        return builder.requestFactory(factory);
    }
}
