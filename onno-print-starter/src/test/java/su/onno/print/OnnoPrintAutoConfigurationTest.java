package su.onno.print;

import su.onno.metadata.MetadataRegistry;
import su.onno.types.RefResolver;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.AccessService;
import su.onno.service.WebProperties;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OnnoPrintAutoConfigurationTest {

    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OnnoPrintAutoConfiguration.class));

    private final ApplicationContextRunner app = bare
            .withBean(MetadataRegistry.class, () -> mock(MetadataRegistry.class))
            .withBean(RefResolver.class, () -> mock(RefResolver.class))
            .withBean(CatalogQueryService.class, () -> mock(CatalogQueryService.class))
            .withBean(DocumentQueryService.class, () -> mock(DocumentQueryService.class))
            .withBean(AccessService.class, () -> mock(AccessService.class))
            .withBean(WebProperties.class, WebProperties::new);

    @Test
    void registersServiceEndpointsAndContributors() {
        app.run(context -> {
            assertThat(context).hasSingleBean(PrintService.class);
            assertThat(context).hasSingleBean(PrintController.class);
            assertThat(context).hasSingleBean(PrintRecordActions.class);
            assertThat(context).hasSingleBean(PrintSelectionDownloads.class);
            // No Jdbi, no converter configured: built-in templates only, no PDF.
            assertThat(context).doesNotHaveBean(PrintTemplateStore.class);
            assertThat(context).doesNotHaveBean(PdfConverter.class);
            assertThat(context.getBean(PrintService.class).pdfAvailable()).isFalse();
        });
    }

    @Test
    void backsOffWithoutTheUiStarterOrWhenDisabled() {
        bare.run(context -> assertThat(context).doesNotHaveBean(PrintService.class));
        app.withPropertyValues("onno.print.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PrintController.class));
    }

    @Test
    void templateStoreFollowsJdbiAndItsSwitch() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:print-autoconfig;DB_CLOSE_DELAY=-1");
        app.withBean(Jdbi.class, () -> jdbi).run(context -> {
            assertThat(context).hasSingleBean(PrintTemplateStore.class);
            assertThat(context.getBean(PrintService.class).templateUploadsEnabled()).isTrue();
        });
        app.withBean(Jdbi.class, () -> jdbi).withPropertyValues("onno.print.templates.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PrintTemplateStore.class));
    }

    @Test
    void pdfConverterIsChosenByProperty() {
        app.withPropertyValues("onno.print.pdf.gotenberg.url=http://localhost:3000")
                .run(context -> assertThat(context).hasSingleBean(GotenbergPdfConverter.class));
        app.withPropertyValues("onno.print.pdf.libreoffice.command=soffice")
                .run(context -> assertThat(context.getBean(PdfConverter.class))
                        .isInstanceOf(LibreOfficePdfConverter.class));
        app.withBean(PdfConverter.class, () -> (document, format) -> document)
                .withPropertyValues("onno.print.pdf.gotenberg.url=http://localhost:3000")
                .run(context -> assertThat(context).doesNotHaveBean(GotenbergPdfConverter.class));
    }
}
