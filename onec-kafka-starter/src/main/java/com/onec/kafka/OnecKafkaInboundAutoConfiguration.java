package com.onec.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, ConcurrentMessageListenerContainer.class})
@ConditionalOnProperty(prefix = "onec.kafka.inbound", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OnecKafkaProperties.class)
public class OnecKafkaInboundAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public Inbox onecInbox(DataSource dataSource) {
        Inbox inbox = new Inbox(Jdbi.create(dataSource));
        inbox.initSchema();
        return inbox;
    }

    @Bean
    @ConditionalOnMissingBean
    public InboundEventRouter inboundEventRouter(ObjectProvider<EventHandler> handlers) {
        return new InboundEventRouter(handlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaEventConsumer kafkaEventConsumer(InboundEventRouter router,
                                                 ObjectMapper objectMapper,
                                                 ObjectProvider<Inbox> inbox,
                                                 ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
                                                 OnecKafkaProperties properties) {
        return new KafkaEventConsumer(router, objectMapper, inbox.getIfAvailable(),
                kafkaTemplate.getIfAvailable(), properties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "onecInboundContainer")
    public ConcurrentMessageListenerContainer<String, String> onecInboundContainer(
            KafkaProperties kafkaProperties,
            KafkaEventConsumer consumer,
            OnecKafkaProperties properties) {

        OnecKafkaProperties.Inbound inbound = properties.getInbound();
        String groupId = (inbound.getGroupId() == null || inbound.getGroupId().isBlank())
                ? properties.getServiceName() + "-inbound"
                : inbound.getGroupId();

        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, inbound.getAutoOffsetReset());

        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(config);

        List<String> topics = inbound.getTopics().isEmpty()
                ? List.of(properties.getTopic())
                : inbound.getTopics();

        ContainerProperties containerProperties = new ContainerProperties(topics.toArray(new String[0]));
        containerProperties.setGroupId(groupId);
        containerProperties.setMessageListener(consumer);

        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setConcurrency(inbound.getConcurrency());
        return container;
    }
}
