package com.onec.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "onec.kafka")
public class OneCKafkaProperties {

    private boolean enabled = true;
    private String serviceName = "onec-service";
    private String topic = "onec.domain-events";
    private int relayBatchSize = 100;
    private Map<String, String> remoteServices = new LinkedHashMap<>();

    @NestedConfigurationProperty
    private Inbound inbound = new Inbound();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getRelayBatchSize() {
        return relayBatchSize;
    }

    public void setRelayBatchSize(int relayBatchSize) {
        this.relayBatchSize = relayBatchSize;
    }

    public Map<String, String> getRemoteServices() {
        return remoteServices;
    }

    public void setRemoteServices(Map<String, String> remoteServices) {
        this.remoteServices = remoteServices;
    }

    public Inbound getInbound() {
        return inbound;
    }

    public void setInbound(Inbound inbound) {
        this.inbound = inbound;
    }

    /** Inbound (consumer) settings. Disabled by default; opt in with {@code onec.kafka.inbound.enabled=true}. */
    public static class Inbound {

        private boolean enabled = false;
        /** Topics to consume. When empty, defaults to the outbound {@code onec.kafka.topic}. */
        private List<String> topics = new ArrayList<>();
        /** Consumer group id. When blank, defaults to {@code <serviceName>-inbound}. */
        private String groupId;
        private int concurrency = 1;
        private String autoOffsetReset = "latest";
        /** When set, messages that fail handling (or are malformed) are published here instead of being redelivered. */
        private String deadLetterTopic;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getTopics() {
            return topics;
        }

        public void setTopics(List<String> topics) {
            this.topics = topics;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public String getAutoOffsetReset() {
            return autoOffsetReset;
        }

        public void setAutoOffsetReset(String autoOffsetReset) {
            this.autoOffsetReset = autoOffsetReset;
        }

        public String getDeadLetterTopic() {
            return deadLetterTopic;
        }

        public void setDeadLetterTopic(String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
        }
    }
}
