package com.onec.ui;

import com.onec.metadata.MetadataRegistry;
import com.onec.numbering.NumberGenerator;
import com.onec.posting.PostingService;
import com.onec.spring.OneCAutoConfiguration;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = OneCAutoConfiguration.class)
@EnableConfigurationProperties(UiProperties.class)
@ConditionalOnBean(MetadataRegistry.class)
@ConditionalOnProperty(prefix = "onec.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UiAutoConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    @Bean
    public SpaIndexController spaIndexController() {
        return new SpaIndexController();
    }

    @Bean
    public ThemeController themeController(UiProperties properties) {
        return new ThemeController(properties);
    }

    @Bean
    public UiAccessService uiAccessService(MetadataRegistry registry) {
        return new UiAccessService(registry);
    }

    @Bean
    public UiEventPublisher uiEventPublisher() {
        return new UiEventPublisher();
    }

    @Bean
    public UiEventController uiEventController(UiEventPublisher publisher) {
        return new UiEventController(publisher);
    }

    @Bean
    public ResolvedMetadataService resolvedMetadataService(MetadataRegistry registry,
                                                           com.onec.ui.UiLayout uiLayout,
                                                           com.onec.ui.UiLayoutResolver layoutResolver) {
        return new ResolvedMetadataService(registry, uiLayout, layoutResolver);
    }

    @Bean
    public MetadataApiController metadataApiController(MetadataRegistry registry,
                                                        com.onec.ui.UiLayout uiLayout,
                                                        com.onec.ui.UiLayoutResolver layoutResolver,
                                                        UiAccessService access,
                                                        ResolvedMetadataService resolvedMetadata) {
        return new MetadataApiController(registry, uiLayout, layoutResolver, access, resolvedMetadata);
    }

    @Bean
    public CatalogQueryService catalogQueryService(MetadataRegistry registry, Jdbi jdbi) {
        return new CatalogQueryService(registry, jdbi);
    }

    @Bean
    public DocumentQueryService documentQueryService(MetadataRegistry registry, Jdbi jdbi) {
        return new DocumentQueryService(registry, jdbi);
    }

    @Bean
    public RegisterQueryService registerQueryService(MetadataRegistry registry, Jdbi jdbi) {
        return new RegisterQueryService(registry, jdbi);
    }

    @Bean
    public GenericCatalogController genericCatalogController(Jdbi jdbi,
                                                              UiProperties properties,
                                                              NumberGenerator numberGenerator,
                                                              CatalogQueryService catalogQueryService,
                                                              UiAccessService access,
                                                              UiEventPublisher eventPublisher) {
        return new GenericCatalogController(jdbi, properties, numberGenerator, catalogQueryService,
                access, eventPublisher);
    }

    @Bean
    public GenericDocumentController genericDocumentController(MetadataRegistry registry, Jdbi jdbi,
                                                                UiProperties properties,
                                                                NumberGenerator numberGenerator,
                                                                PostingService postingService,
                                                                DocumentQueryService documentQueryService,
                                                                UiAccessService access,
                                                                UiEventPublisher eventPublisher) {
        return new GenericDocumentController(registry, jdbi, properties, numberGenerator, postingService,
                documentQueryService, access, eventPublisher);
    }

    @Bean
    public GenericRegisterController genericRegisterController(RegisterQueryService registerQueryService,
                                                               UiAccessService access) {
        return new GenericRegisterController(registerQueryService, access);
    }

    @Bean
    public CurrentUserResolver currentUserResolver(com.onec.ui.UiLayout uiLayout,
                                                   MetadataRegistry registry, Jdbi jdbi) {
        return new CurrentUserResolver(uiLayout, registry, jdbi);
    }

    @Bean
    public DivKitController divKitController(com.onec.ui.UiLayout uiLayout,
                                             com.onec.ui.UiLayoutResolver layoutResolver,
                                             com.onec.ui.UiProfileResolver profileResolver,
                                             UiAccessService access,
                                             CurrentUserResolver currentUserResolver,
                                             ResolvedMetadataService resolvedMetadata,
                                             CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             RegisterQueryService registerQueryService) {
        return new DivKitController(uiLayout, layoutResolver, profileResolver, access, currentUserResolver,
                resolvedMetadata, catalogQueryService, documentQueryService, registerQueryService);
    }

}
