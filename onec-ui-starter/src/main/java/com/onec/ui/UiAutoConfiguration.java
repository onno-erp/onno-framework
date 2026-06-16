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
@EnableConfigurationProperties({UiProperties.class, UpdateProperties.class})
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
    public ListDataController listDataController(CatalogQueryService catalogQueryService,
                                                DocumentQueryService documentQueryService,
                                                UiAccessService access,
                                                UiActionResolver uiActionResolver) {
        return new ListDataController(catalogQueryService, documentQueryService, access, uiActionResolver);
    }

    @Bean
    public UiActionResolver uiActionResolver(
            org.springframework.beans.factory.ObjectProvider<com.onec.ui.EntityView> entityViews) {
        return new UiActionResolver(entityViews.orderedStream().toList());
    }

    @Bean
    public ActionController actionController(CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             UiAccessService access,
                                             UiActionResolver uiActionResolver) {
        return new ActionController(catalogQueryService, documentQueryService, access, uiActionResolver);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public OnecValidationExceptionHandler onecValidationExceptionHandler() {
        return new OnecValidationExceptionHandler();
    }

    @Bean
    public ThemeController themeController(UiProperties properties, com.onec.ui.UiLayout uiLayout,
                                          org.springframework.beans.factory.ObjectProvider<UpdateChecker> updateChecker) {
        return new ThemeController(properties, uiLayout, updateChecker);
    }

    /**
     * Polls onec-cloud for a newer framework release and exposes the result through {@code /config}.
     * Disabled with {@code onec.ui.update-check.enabled=false}; otherwise on by default and fail-silent.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "onec.ui.update-check", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public UpdateChecker updateChecker(UpdateProperties updateProperties,
                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new UpdateChecker(updateProperties, OnecBuildInfo.version(), objectMapper);
    }

    @Bean
    public LoginDivController loginDivController(
            org.springframework.beans.factory.ObjectProvider<com.onec.auth.spi.AuthMethodsProvider> authMethods,
            org.springframework.beans.factory.ObjectProvider<com.onec.auth.spi.AuthMethodsContributor> contributors) {
        return new LoginDivController(authMethods, contributors);
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
    public InformationRegisterQueryService informationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi) {
        return new InformationRegisterQueryService(registry, jdbi);
    }

    @Bean
    public RelatedListReader relatedListReader(FieldHintResolver fieldHintResolver, MetadataRegistry registry,
                                               CatalogQueryService catalogQueryService,
                                               InformationRegisterQueryService informationRegisterQueryService,
                                               UiAccessService access) {
        return new RelatedListReader(fieldHintResolver, registry, catalogQueryService,
                informationRegisterQueryService, access);
    }

    @Bean
    public CatalogCommandService catalogCommandService(Jdbi jdbi, UiProperties properties,
                                                       NumberGenerator numberGenerator,
                                                       CatalogQueryService catalogQueryService,
                                                       UiAccessService access,
                                                       org.springframework.context.ApplicationEventPublisher events,
                                                       com.onec.security.SecretCipher secretCipher) {
        return new CatalogCommandService(jdbi, properties, numberGenerator, catalogQueryService,
                access, events, secretCipher);
    }

    @Bean
    public DocumentCommandService documentCommandService(MetadataRegistry registry, Jdbi jdbi,
                                                         UiProperties properties,
                                                         NumberGenerator numberGenerator,
                                                         PostingService postingService,
                                                         DocumentQueryService documentQueryService,
                                                         UiAccessService access,
                                                         org.springframework.context.ApplicationEventPublisher events,
                                                         com.onec.security.SecretCipher secretCipher) {
        return new DocumentCommandService(registry, jdbi, properties, numberGenerator, postingService,
                documentQueryService, access, events, secretCipher);
    }

    @Bean
    public GenericCatalogController genericCatalogController(CatalogQueryService catalogQueryService,
                                                              UiAccessService access,
                                                              CatalogCommandService catalogCommandService,
                                                              RelatedListReader relatedListReader) {
        return new GenericCatalogController(catalogQueryService, access, catalogCommandService,
                relatedListReader);
    }

    @Bean
    public GenericDocumentController genericDocumentController(DocumentQueryService documentQueryService,
                                                                UiAccessService access,
                                                                DocumentCommandService documentCommandService,
                                                                RelatedListReader relatedListReader) {
        return new GenericDocumentController(documentQueryService, access, documentCommandService,
                relatedListReader);
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
                                             RegisterQueryService registerQueryService,
                                             UiActionResolver uiActionResolver,
                                             RelatedListReader relatedListReader) {
        return new DivKitController(layoutSet, layoutResolver, profileResolver, access, currentUserResolver,
                resolvedMetadata, uiViewResolver, pageResolver, catalogQueryService, documentQueryService,
                registerQueryService, uiActionResolver, relatedListReader);
    }

}
