package su.onno.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "onno.kafka")
public class OnnoKafkaProperties {

    /** Master switch for the outbound relay beans. */
    private boolean enabled = true;

    /** CloudEvent {@code source} for emitted events; also the prefix for the default inbound group id. */
    private String serviceName = "onno-service";

    /** Outbound topic events are published to (and the inbound default when no inbound topics are set). */
    private String topic = "onno.domain-events";

    /** Maximum number of outbox rows drained per {@code relayPending()} call. */
    private int relayBatchSize = 100;

    /** Service name to base-URL map used by {@code RemoteRefClient} to resolve cross-service refs. */
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

    /** Inbound (consumer) settings. Disabled by default; opt in with {@code onno.kafka.inbound.enabled=true}. */
    public static class Inbound {

        /** Opt-in switch for the inbound consumer. Off by default. */
        private boolean enabled = false;
        /** Topics to consume. When empty, defaults to the outbound {@code onno.kafka.topic}. */
        private List<String> topics = new ArrayList<>();
        /** Consumer group id. When blank, defaults to {@code <serviceName>-inbound}. */
        private String groupId;
        /** Listener container concurrency (number of consumer threads). */
        private int concurrency = 1;
        /** Kafka offset reset policy applied when no committed offset exists ({@code latest} / {@code earliest}). */
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
