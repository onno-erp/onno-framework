package su.onno.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.metadata.MetadataRegistry;
import su.onno.web.CatalogCommandService;
import su.onno.web.CatalogQueryService;
import su.onno.web.DocumentCommandService;
import su.onno.web.DocumentQueryService;
import su.onno.web.AccessService;
import su.onno.web.UiAutoConfiguration;

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
        AccessService.class
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
                                                           AccessService access,
                                                           CatalogCsvImportService catalogImports,
                                                           DocumentCsvImportService documentImports,
                                                           OnnoImportProperties properties,
                                                           ObjectMapper objectMapper) {
        return new CatalogImportController(catalogQueryService, documentQueryService, access,
                catalogImports, documentImports, properties, objectMapper);
    }
}
