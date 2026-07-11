package su.onno.ui;

import su.onno.metadata.MetadataRegistry;
import su.onno.numbering.NumberGenerator;
import su.onno.posting.PostingService;
import su.onno.spring.OnnoAutoConfiguration;
import su.onno.ui.comments.CommentAuthorAvatars;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = OnnoAutoConfiguration.class)
@EnableConfigurationProperties({UiProperties.class, UpdateProperties.class})
@ConditionalOnBean(MetadataRegistry.class)
@ConditionalOnProperty(prefix = "onno.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UiAutoConfiguration implements WebMvcConfigurer {

    // The SPA shell with onno.ui.path baked in — shared by the deep-link fallback resolver and the
    // root controller so both serve a shell that knows the app's mount prefix.
    private final SpaIndexHtml spaIndexHtml;
    private final UiProperties uiProperties;
    private final WidgetPluginScanner widgetPluginScanner;

    public UiAutoConfiguration(UiProperties uiProperties) {
        this.uiProperties = uiProperties;
        this.spaIndexHtml = new SpaIndexHtml(uiProperties.getPath());
        this.widgetPluginScanner = uiProperties.getPlugins().isEnabled()
                ? new WidgetPluginScanner(uiProperties.getPlugins().getLocation())
                : null;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve compiled widget plugins under {path}/plugins/** BEFORE the catch-all so a plugin
        // module isn't swallowed by the SPA's index.html fallback. Registered first → higher precedence.
        if (widgetPluginScanner != null) {
            String base = "/".equals(uiProperties.getPath()) ? "" : uiProperties.getPath();
            registry.addResourceHandler(base + "/plugins/**")
                    .addResourceLocations(widgetPluginScanner.serveLocation())
                    .resourceChain(true);
        }
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true)
                .addResolver(new SpaResourceResolver(spaIndexHtml));
    }

    @Bean
    @ConditionalOnProperty(prefix = "onno.ui.plugins", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public WidgetPluginScanner widgetPluginScanner() {
        return widgetPluginScanner != null ? widgetPluginScanner
                : new WidgetPluginScanner(uiProperties.getPlugins().getLocation());
    }

    @Bean
    public SpaIndexController spaIndexController() {
        return new SpaIndexController(spaIndexHtml);
    }

    /**
     * The resolved chrome strings, layered later-wins: English {@link UiMessages#DEFAULTS} → the
     * {@code onno.ui.locale} bundle (e.g. the shipped {@code ru}) → explicit {@code onno.ui.messages}
     * per-key overrides.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public UiMessages uiMessages(UiProperties properties) {
        java.util.Map<String, String> merged =
                new java.util.LinkedHashMap<>(UiMessageBundles.load(properties.getLocale()));
        merged.putAll(properties.getMessages()); // explicit per-key overrides win over the locale bundle
        return new UiMessages(merged);
    }

    @Bean
    public SettingsController settingsController(MetadataRegistry registry,
                                                su.onno.repository.ConstantManager constantManager,
                                                UiAccessService access) {
        return new SettingsController(registry, constantManager, access);
    }

    @Bean
    public ListDataController listDataController(CatalogQueryService catalogQueryService,
                                                DocumentQueryService documentQueryService,
                                                UiAccessService access,
                                                UiActionResolver uiActionResolver,
                                                UiViewResolver uiViewResolver) {
        return new ListDataController(catalogQueryService, documentQueryService, access, uiActionResolver,
                uiViewResolver);
    }

    @Bean
    public UiActionResolver uiActionResolver(
            org.springframework.beans.factory.ObjectProvider<su.onno.ui.EntityView> entityViews) {
        return new UiActionResolver(entityViews.orderedStream().toList());
    }

    @Bean
    public ActionController actionController(CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             UiAccessService access,
                                             UiActionResolver uiActionResolver,
                                             UiProperties uiProperties) {
        return new ActionController(catalogQueryService, documentQueryService, access, uiActionResolver,
                uiProperties);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public OnnoValidationExceptionHandler onnoValidationExceptionHandler() {
        return new OnnoValidationExceptionHandler();
    }

    @Bean
    public ThemeController themeController(UiProperties properties, su.onno.ui.UiLayout uiLayout,
                                          UiMessages uiMessages,
                                          org.springframework.beans.factory.ObjectProvider<UpdateChecker> updateChecker,
                                          org.springframework.beans.factory.ObjectProvider<WidgetPluginScanner> widgetPlugins) {
        return new ThemeController(properties, uiLayout, uiMessages, updateChecker, widgetPlugins);
    }

    /**
     * Polls onno-cloud for a newer framework release and exposes the result through {@code /config}.
     * Disabled with {@code onno.ui.update-check.enabled=false}; otherwise on by default and fail-silent.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "onno.ui.update-check", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public UpdateChecker updateChecker(UpdateProperties updateProperties,
                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new UpdateChecker(updateProperties, OnnoBuildInfo.version(), objectMapper);
    }

    @Bean
    public LoginDivController loginDivController(
            org.springframework.beans.factory.ObjectProvider<su.onno.auth.spi.AuthMethodsProvider> authMethods,
            org.springframework.beans.factory.ObjectProvider<su.onno.auth.spi.AuthMethodsContributor> contributors,
            UiMessages uiMessages,
            su.onno.ui.LayoutSet layoutSet,
            UiProperties properties) {
        // Branding is viewport-independent — take the desktop layout's, the same source DivKitController uses.
        su.onno.ui.BrandingConfig branding = layoutSet.forViewport(su.onno.ui.Viewport.DESKTOP).shell().branding();
        return new LoginDivController(authMethods, contributors, uiMessages, branding, properties);
    }

    @Bean
    public UiAccessService uiAccessService(MetadataRegistry registry) {
        return new UiAccessService(registry);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public UiEventPublisher uiEventPublisher(UiAccessService access) {
        return new UiEventPublisher(access);
    }

    @Bean
    public UiEventController uiEventController(UiEventPublisher publisher, UiAccessService access,
                                              CurrentUserResolver currentUserResolver) {
        return new UiEventController(publisher, access, currentUserResolver);
    }

    /**
     * Bridges the cross-node {@link su.onno.cluster.ClusterEventBus} into the local SSE stream so a
     * write on one node lights up browsers connected to any node. With the default no-op bus this is
     * inert. See {@link ClusterUiBridge} for why received events bypass the Spring event bus.
     */
    @Bean
    public ClusterUiBridge clusterUiBridge(su.onno.cluster.ClusterEventBus clusterEventBus,
                                           UiEventPublisher publisher) {
        return new ClusterUiBridge(clusterEventBus, publisher);
    }

    /**
     * Tracks who is viewing each record for record-level collaboration markers. It subscribes to the
     * {@link su.onno.cluster.ClusterEventBus} for peer presence and pushes viewer-set changes onto the SSE
     * stream. With the default no-op bus it is a single-node, in-memory registry.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.ui.presence.PresenceRegistry presenceRegistry(su.onno.cluster.ClusterEventBus clusterEventBus,
                                                                 UiEventPublisher publisher) {
        return new su.onno.ui.presence.PresenceRegistry(clusterEventBus, publisher);
    }

    @Bean
    public su.onno.ui.presence.PresenceController presenceController(su.onno.ui.presence.PresenceRegistry presenceRegistry,
                                                                     UiAccessService access,
                                                                     CurrentUserResolver currentUserResolver,
                                                                     CommentAuthorAvatars authorAvatars) {
        return new su.onno.ui.presence.PresenceController(presenceRegistry, access, currentUserResolver, authorAvatars);
    }

    @Bean
    public FieldHintResolver fieldHintResolver(
            org.springframework.beans.factory.ObjectProvider<su.onno.ui.EntityView> entityViews) {
        return new FieldHintResolver(entityViews.orderedStream().toList());
    }

    /**
     * Resolves a user's avatar image URL from the identity catalog's avatar/image-hinted column.
     * Always-on (not gated on {@code onno.comments.enabled}) so both the comments panel and record-level
     * presence markers render a viewer's photo from the one identity source.
     */
    @Bean
    public CommentAuthorAvatars commentAuthorAvatars(su.onno.ui.UiLayout uiLayout, MetadataRegistry registry,
                                                     FieldHintResolver fieldHintResolver, Jdbi jdbi) {
        return new CommentAuthorAvatars(uiLayout, registry, fieldHintResolver, jdbi);
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
    public CatalogCommandService catalogCommandService(MetadataRegistry registry, Jdbi jdbi,
                                                       UiProperties properties,
                                                       NumberGenerator numberGenerator,
                                                       CatalogQueryService catalogQueryService,
                                                       UiAccessService access,
                                                       org.springframework.context.ApplicationEventPublisher events,
                                                       su.onno.security.SecretCipher secretCipher) {
        return new CatalogCommandService(registry, jdbi, properties, numberGenerator, catalogQueryService,
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
                                                         su.onno.security.SecretCipher secretCipher) {
        return new DocumentCommandService(registry, jdbi, properties, numberGenerator, postingService,
                documentQueryService, access, events, secretCipher);
    }

    @Bean
    public GenericCatalogController genericCatalogController(CatalogQueryService catalogQueryService,
                                                              UiAccessService access,
                                                              CatalogCommandService catalogCommandService,
                                                              RelatedListReader relatedListReader,
                                                              UiMessages uiMessages) {
        return new GenericCatalogController(catalogQueryService, access, catalogCommandService,
                relatedListReader, uiMessages);
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
    public RegisterListController registerListController(RegisterQueryService registerQueryService,
                                                         UiAccessService access, UiMessages uiMessages) {
        return new RegisterListController(registerQueryService, access, uiMessages);
    }

    @Bean
    public CurrentUserResolver currentUserResolver(su.onno.ui.UiLayout uiLayout,
                                                   MetadataRegistry registry,
                                                   FieldHintResolver fieldHintResolver, Jdbi jdbi) {
        return new CurrentUserResolver(uiLayout, registry, fieldHintResolver, jdbi);
    }

    @Bean
    public UiViewResolver uiViewResolver(ResolvedMetadataService resolvedMetadata,
                                         org.springframework.beans.factory.ObjectProvider<su.onno.ui.EntityView> entityViews,
                                         UiProperties properties) {
        return new UiViewResolver(resolvedMetadata, entityViews.orderedStream().toList(),
                properties.getList().getDefaultFeed(), properties.getList().getPageSize());
    }

    @Bean
    public PageResolver pageResolver(
            org.springframework.beans.factory.ObjectProvider<su.onno.ui.Page> pages) {
        return new PageResolver(pages.orderedStream().toList());
    }

    @Bean
    public DivKitController divKitController(su.onno.ui.LayoutSet layoutSet,
                                             su.onno.ui.UiLayoutResolver layoutResolver,
                                             su.onno.ui.UiProfileResolver profileResolver,
                                             UiAccessService access,
                                             CurrentUserResolver currentUserResolver,
                                             ResolvedMetadataService resolvedMetadata,
                                             UiViewResolver uiViewResolver,
                                             PageResolver pageResolver,
                                             CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             RegisterQueryService registerQueryService,
                                             UiActionResolver uiActionResolver,
                                             RelatedListReader relatedListReader,
                                             UiProperties uiProperties,
                                             UiMessages uiMessages,
                                             org.springframework.beans.factory.ObjectProvider<su.onno.ui.comments.CommentProperties> commentProperties,
                                             org.springframework.beans.factory.ObjectProvider<su.onno.ui.notifications.NotificationProperties> notificationProperties) {
        return new DivKitController(layoutSet, layoutResolver, profileResolver, access, currentUserResolver,
                resolvedMetadata, uiViewResolver, pageResolver, catalogQueryService, documentQueryService,
                registerQueryService, uiActionResolver, relatedListReader, uiProperties, uiMessages, commentProperties,
                notificationProperties);
    }

}
