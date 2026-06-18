package su.onno.ui.media;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

/**
 * Wires the {@code /api/media} binary-upload endpoint and its default filesystem storage. Both the
 * endpoint and the storage are gated on {@code onno.media.enabled} (default true) and a servlet web
 * application. The {@link FilesystemMediaStorage} default backs off the moment an application or
 * connector contributes its own {@link MediaStorage} bean (e.g. S3-compatible), so storage is
 * swapped by addition, not by editing the framework.
 *
 * <p>Runs before Spring's {@link MultipartAutoConfiguration} so its {@link MultipartConfigElement}
 * (sized from {@code onno.media.max-file-size}) wins over the 1&nbsp;MB container default that would
 * otherwise reject realistic image uploads before they reach the controller.
 */
@AutoConfiguration(before = MultipartAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MediaProperties.class)
@ConditionalOnProperty(prefix = "onno.media", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MediaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MediaStorage.class)
    public MediaStorage filesystemMediaStorage(MediaProperties properties) {
        return new FilesystemMediaStorage(Path.of(properties.getFilesystem().getDirectory()),
                properties.getPublicBasePath());
    }

    @Bean
    public MediaController mediaController(MediaStorage storage, MediaProperties properties) {
        return new MediaController(storage, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MultipartConfigElement multipartConfigElement(MediaProperties properties) {
        DataSize max = properties.getMaxFileSize();
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(max);
        // Allow a little overhead for multipart framing on top of the single file.
        factory.setMaxRequestSize(DataSize.ofBytes(max.toBytes() + DataSize.ofKilobytes(64).toBytes()));
        return factory.createMultipartConfig();
    }
}
