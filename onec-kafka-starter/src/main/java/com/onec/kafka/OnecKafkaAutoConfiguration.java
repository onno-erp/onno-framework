package com.onec.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.messaging.OutboxWriter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnBean({OutboxWriter.class, KafkaTemplate.class})
@ConditionalOnProperty(prefix = "onec.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OnecKafkaProperties.class)
public class OnecKafkaAutoConfiguration {

    @Bean
    public ServiceRegistry serviceRegistry(OnecKafkaProperties properties) {
        return new ServiceRegistry(properties);
    }

    @Bean
    public RemoteRefClient remoteRefClient(ServiceRegistry serviceRegistry,
                                           RestClient.Builder restClientBuilder) {
        return new RemoteRefClient(serviceRegistry, restClientBuilder);
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxWriter outboxWriter,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   OnecKafkaProperties properties) {
        return new OutboxRelay(outboxWriter, kafkaTemplate, objectMapper, properties);
    }
}
