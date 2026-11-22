package su.onno.print;

import su.onno.metadata.MetadataRegistry;
import su.onno.types.RefResolver;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.AccessService;
import su.onno.web.UiAutoConfiguration;
import su.onno.service.WebProperties;

import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the print form service, its endpoints, the record "Print" actions and list batch
 * printing; the template store when uploads are enabled; and a PDF converter when one is configured.
 */
@AutoConfiguration(after = UiAutoConfiguration.class)
@ConditionalOnBean({MetadataRegistry.class, RefResolver.class, CatalogQueryService.class,
        DocumentQueryService.class, AccessService.class})
@EnableConfigurationProperties(OnnoPrintProperties.class)
@ConditionalOnProperty(prefix = "onno.print", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnnoPrintAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Jdbi.class)
    @ConditionalOnProperty(prefix = "onno.print.templates", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PrintTemplateStore printTemplateStore(Jdbi jdbi) {
        return new PrintTemplateStore(jdbi);
    }

    @Bean
    @ConditionalOnMissingBean(PdfConverter.class)
    @ConditionalOnProperty(prefix = "onno.print.pdf.gotenberg", name = "url")
    public GotenbergPdfConverter gotenbergPdfConverter(OnnoPrintProperties properties) {
        OnnoPrintProperties.Gotenberg gotenberg = properties.getPdf().getGotenberg();
        return new GotenbergPdfConverter(gotenberg.getUrl(), gotenberg.getTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(PdfConverter.class)
    @ConditionalOnProperty(prefix = "onno.print.pdf.libreoffice", name = "command")
    public LibreOfficePdfConverter libreOfficePdfConverter(OnnoPrintProperties properties) {
        OnnoPrintProperties.LibreOffice libreoffice = properties.getPdf().getLibreoffice();
        return new LibreOfficePdfConverter(libreoffice.getCommand(), libreoffice.getTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PrintService printService(ObjectProvider<PrintForm<?>> forms, MetadataRegistry registry,
                                     RefResolver refs, ResourceLoader resources,
                                     ObjectProvider<PrintTemplateStore> store, ObjectProvider<PdfConverter> pdf,
                                     OnnoPrintProperties properties,
                                     ObjectProvider<su.onno.access.RoleGrants> grants) {
        // Uploaded templates are keyed by entity scope (catalogs/{name}, documents/{name}), not by
        // class name, so a package move keeps them; renaming the entity orphans them.
        Map<Class<?>, String> scopes = new HashMap<>();
        registry.allCatalogs().forEach(d -> scopes.put(d.javaClass(), "catalogs/" + route(d.logicalName())));
        registry.allDocuments().forEach(d -> scopes.put(d.javaClass(), "documents/" + route(d.logicalName())));
        PrintService service = new PrintService((List) forms.orderedStream().toList(), scopes, refs, resources,
                store.getIfAvailable(), pdf.getIfAvailable(), properties.getTemplates().getMaxSize().toBytes());
        service.requireKnownRoles(grants.getIfAvailable(su.onno.access.RoleGrants::open));
        return service;
    }

    @Bean
    public PrintRecordActions printRecordActions(PrintService print) {
        return new PrintRecordActions(print);
    }

    @Bean
    public PrintSelectionDownloads printSelectionDownloads(PrintService print) {
        return new PrintSelectionDownloads(print);
    }

    @Bean
    public PrintController printController(PrintService print, CatalogQueryService catalogs,
                                           DocumentQueryService documents, AccessService access,
                                           RefResolver refs, OnnoPrintProperties properties,
                                           WebProperties webProperties) {
        return new PrintController(print, catalogs, documents, access, refs,
                properties.getBatch().getMaxRecords(),
                webProperties.isReadOnly());
    }

    private static String route(String logicalName) {
        return logicalName.replace(' ', '_');
    }
}
