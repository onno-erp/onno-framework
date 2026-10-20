package su.onno.print;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.types.RefResolver;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiAutoConfiguration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Registers the print form service, its download endpoints and the record "Print" actions. */
@AutoConfiguration(after = UiAutoConfiguration.class)
@ConditionalOnBean({MetadataRegistry.class, RefResolver.class, CatalogQueryService.class,
        DocumentQueryService.class, UiAccessService.class})
@EnableConfigurationProperties(OnnoPrintProperties.class)
@ConditionalOnProperty(prefix = "onno.print", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnnoPrintAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PrintService printService(ObjectProvider<PrintForm<?>> forms, MetadataRegistry registry,
                                     RefResolver refs, ResourceLoader resources) {
        Set<Class<?>> printable = new HashSet<>();
        registry.allCatalogs().stream().map(CatalogDescriptor::javaClass).forEach(printable::add);
        registry.allDocuments().stream().map(DocumentDescriptor::javaClass).forEach(printable::add);
        return new PrintService((List) forms.orderedStream().toList(), printable, refs, resources);
    }

    @Bean
    public PrintRecordActions printRecordActions(PrintService print) {
        return new PrintRecordActions(print);
    }

    @Bean
    public PrintController printController(PrintService print, CatalogQueryService catalogs,
                                           DocumentQueryService documents, UiAccessService access,
                                           RefResolver refs) {
        return new PrintController(print, catalogs, documents, access, refs);
    }
}
