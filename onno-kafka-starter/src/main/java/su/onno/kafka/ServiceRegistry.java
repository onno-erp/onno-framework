package su.onno.kafka;

import java.util.List;
import java.util.Optional;

public class ServiceRegistry {

    private final OnnoKafkaProperties properties;

    public ServiceRegistry(OnnoKafkaProperties properties) {
        this.properties = properties;
    }

    public Optional<ServiceDescriptor> find(String serviceName) {
        String baseUrl = properties.getRemoteServices().get(serviceName);
        return baseUrl == null ? Optional.empty() : Optional.of(new ServiceDescriptor(serviceName, baseUrl));
    }

    public List<ServiceDescriptor> all() {
        return properties.getRemoteServices().entrySet().stream()
                .map(e -> new ServiceDescriptor(e.getKey(), e.getValue()))
                .toList();
    }
}
