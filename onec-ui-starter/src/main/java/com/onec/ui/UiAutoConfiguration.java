package com.onec.ui;

import com.onec.metadata.MetadataRegistry;
import com.onec.numbering.NumberGenerator;
import com.onec.posting.PostingService;
import com.onec.spring.OnecAutoConfiguration;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = OnecAutoConfiguration.class)
@EnableConfigurationProperties(UiProperties.class)
@ConditionalOnBean(MetadataRegistry.class)
@ConditionalOnProperty(prefix = "onec.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UiAutoConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    @Bean
    public SpaIndexController spaIndexController() {
        return new SpaIndexController();
    }

    @Bean
    public SettingsController settingsController(MetadataRegistry registry,
                                                com.onec.repository.ConstantManager constantManager,
                                                UiAccessService access) {
        return new SettingsController(registry, constantManager, access);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public OnecValidationExceptionHandler oneCValidationExceptionHandler() {
        return new OnecValidationExceptionHandler();
    }

    @Bean
    public ThemeController themeController(UiProperties properties) {
        return new ThemeController(properties);
    }

    @Bean
    public LoginDivController loginDivController(
            org.springframework.beans.factory.ObjectProvider<com.onec.auth.spi.AuthMethodsProvider> authMethods) {
        return new LoginDivController(authMethods);
    }

    @Bean
    public UiAccessService uiAccessService(MetadataRegistry registry) {
        return new UiAccessService(registry);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public UiEventPublisher uiEventPublisher() {
        return new UiEventPublisher();
    }

    @Bean
    public UiEventController uiEventController(UiEventPublisher publisher) {
        return new UiEventController(publisher);
    }

    @Bean
    public FieldHintResolver fieldHintResolver(
            org.springframework.beans.factory.ObjectProvider<com.onec.ui.EntityView> entityViews) {
        return new FieldHintResolver(entityViews.orderedStream().toList());
    }

    @Bean
    public ResolvedMetadataService resolvedMetadataService(MetadataRegistry registry,
                                                           FieldHintResolver fieldHintResolver) {
        return new ResolvedMetadataService(registry, fieldHintResolver);
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
                                                              org.springframework.context.ApplicationEventPublisher events,
                                                              com.onec.security.SecretCipher secretCipher) {
        return new GenericCatalogController(jdbi, properties, numberGenerator, catalogQueryService,
                access, events, secretCipher);
    }

    @Bean
    public GenericDocumentController genericDocumentController(MetadataRegistry registry, Jdbi jdbi,
                                                                UiProperties properties,
                                                                NumberGenerator numberGenerator,
                                                                PostingService postingService,
                                                                DocumentQueryService documentQueryService,
                                                                UiAccessService access,
                                                                org.springframework.context.ApplicationEventPublisher events,
                                                                com.onec.security.SecretCipher secretCipher) {
        return new GenericDocumentController(registry, jdbi, properties, numberGenerator, postingService,
                documentQueryService, access, events, secretCipher);
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
    public UiViewResolver uiViewResolver(ResolvedMetadataService resolvedMetadata,
                                         org.springframework.beans.factory.ObjectProvider<com.onec.ui.EntityView> entityViews) {
        return new UiViewResolver(resolvedMetadata, entityViews.orderedStream().toList());
    }

    @Bean
    public PageResolver pageResolver(
            org.springframework.beans.factory.ObjectProvider<com.onec.ui.Page> pages) {
        return new PageResolver(pages.orderedStream().toList());
    }

    @Bean
    public DivKitController divKitController(com.onec.ui.LayoutSet layoutSet,
                                             com.onec.ui.UiLayoutResolver layoutResolver,
                                             com.onec.ui.UiProfileResolver profileResolver,
                                             UiAccessService access,
                                             CurrentUserResolver currentUserResolver,
                                             ResolvedMetadataService resolvedMetadata,
                                             UiViewResolver uiViewResolver,
                                             PageResolver pageResolver,
                                             CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             RegisterQueryService registerQueryService) {
        return new DivKitController(layoutSet, layoutResolver, profileResolver, access, currentUserResolver,
                resolvedMetadata, uiViewResolver, pageResolver, catalogQueryService, documentQueryService,
                registerQueryService);
    }

}
