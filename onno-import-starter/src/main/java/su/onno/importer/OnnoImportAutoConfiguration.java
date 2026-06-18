package su.onno.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentCommandService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiAutoConfiguration;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = UiAutoConfiguration.class)
@ConditionalOnBean({
        MetadataRegistry.class,
        CatalogQueryService.class,
        CatalogCommandService.class,
        DocumentQueryService.class,
        DocumentCommandService.class,
        UiAccessService.class
})
@EnableConfigurationProperties(OnnoImportProperties.class)
@ConditionalOnProperty(prefix = "onno.import", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnnoImportAutoConfiguration {

    @Bean
    public CatalogCsvImportService catalogCsvImportService(Jdbi jdbi,
                                                           CatalogCommandService catalogCommandService,
                                                           OnnoImportProperties properties) {
        return new CatalogCsvImportService(jdbi, catalogCommandService, properties);
    }

    @Bean
    public DocumentCsvImportService documentCsvImportService(Jdbi jdbi,
                                                             DocumentCommandService documentCommandService,
                                                             OnnoImportProperties properties) {
        return new DocumentCsvImportService(jdbi, documentCommandService, properties);
    }

    @Bean
    public CatalogImportController catalogImportController(CatalogQueryService catalogQueryService,
                                                           DocumentQueryService documentQueryService,
                                                           UiAccessService access,
                                                           CatalogCsvImportService catalogImports,
                                                           DocumentCsvImportService documentImports,
                                                           OnnoImportProperties properties,
                                                           ObjectMapper objectMapper) {
        return new CatalogImportController(catalogQueryService, documentQueryService, access,
                catalogImports, documentImports, properties, objectMapper);
    }
}
