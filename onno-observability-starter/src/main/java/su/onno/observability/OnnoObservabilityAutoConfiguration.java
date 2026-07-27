package su.onno.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
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

    @Bean
    @ConditionalOnMissingBean(TelemetrySink.class)
    public TelemetrySink telemetrySink(
            TelemetryProperties properties,
            org.springframework.beans.factory.ObjectProvider<OpenTelemetry> openTelemetry) {
        return new OpenTelemetryTelemetrySink(
                openTelemetry.getIfAvailable(GlobalOpenTelemetry::get), properties);
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
