package su.onno.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.messaging.OutboxWriter;

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
@ConditionalOnProperty(prefix = "onno.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OnnoKafkaProperties.class)
public class OnnoKafkaAutoConfiguration {

    @Bean
    public ServiceRegistry serviceRegistry(OnnoKafkaProperties properties) {
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
                                   OnnoKafkaProperties properties) {
        return new OutboxRelay(outboxWriter, kafkaTemplate, objectMapper, properties);
    }
}
