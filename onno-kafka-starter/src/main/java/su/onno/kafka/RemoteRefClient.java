package su.onno.kafka;

import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

public class RemoteRefClient {

    private final ServiceRegistry serviceRegistry;
    private final RestClient.Builder restClientBuilder;

    public RemoteRefClient(ServiceRegistry serviceRegistry, RestClient.Builder restClientBuilder) {
        this.serviceRegistry = serviceRegistry;
        this.restClientBuilder = restClientBuilder;
    }

    public Map<?, ?> resolve(String serviceName, String entityPath, UUID id) {
        ServiceDescriptor service = serviceRegistry.find(serviceName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown remote service: " + serviceName));
        return restClientBuilder.baseUrl(service.baseUrl())
                .build()
                .get()
                .uri("/{entityPath}/{id}", entityPath, id)
                .retrieve()
                .body(Map.class);
    }
}
