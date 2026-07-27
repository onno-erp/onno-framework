package su.onno.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(TelemetryProperties.class)
@ConditionalOnProperty(prefix = "onno.telemetry", name = "enabled", havingValue = "true")
public class OnnoObservabilityAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TelemetrySink.class)
    public BufferedHttpTelemetrySink telemetrySink(
            TelemetryProperties properties,
            ObjectMapper objectMapper) {
        return new BufferedHttpTelemetrySink(properties, objectMapper);
    }

    @Bean
    @ConditionalOnBean(TelemetrySink.class)
    @ConditionalOnMissingBean
    public TelemetryRecorder telemetryRecorder(TelemetrySink sink) {
        return new TelemetryRecorder(sink);
    }

    @Bean
    @ConditionalOnBean(TelemetryRecorder.class)
    @ConditionalOnMissingBean
    public FrameworkTelemetryListener frameworkTelemetryListener(TelemetryRecorder recorder) {
        return new FrameworkTelemetryListener(recorder);
    }

    @Bean
    @ConditionalOnBean(TelemetrySink.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public BrowserTelemetryController browserTelemetryController(TelemetrySink sink) {
        return new BrowserTelemetryController(sink);
    }
}
