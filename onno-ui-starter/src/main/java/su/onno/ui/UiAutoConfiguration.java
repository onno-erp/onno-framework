package su.onno.ui;

import su.onno.access.AccessSubject;
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
@EnableConfigurationProperties({UiProperties.class, UpdateProperties.class, RecordAccessProperties.class})
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

    /**
     * Puts the {@link AccessSubject} argument resolver <em>first</em> on the MVC handler adapter.
     * Registering it through {@code addArgumentResolvers} is not enough: custom resolvers run after
     * the built-in ones, and Spring Data's web support answers any interface-typed parameter with a
     * projection proxy — a handler's {@code AccessSubject} would silently become an empty proxy.
     */
    @Bean
    public static org.springframework.beans.factory.config.BeanPostProcessor accessSubjectArgumentResolverRegistrar(
            org.springframework.beans.factory.ObjectProvider<AccessSubjectResolver> accessSubjectResolver) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter adapter
                        && adapter.getArgumentResolvers() != null) {
                    java.util.List<org.springframework.web.method.support.HandlerMethodArgumentResolver> resolvers =
                            new java.util.ArrayList<>(adapter.getArgumentResolvers());
                    resolvers.add(0, new AccessSubjectArgumentResolver(accessSubjectResolver));
                    adapter.setArgumentResolvers(resolvers);
                }
                return bean;
            }
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String base = "/".equals(spaIndexHtml.basePath()) ? "" : spaIndexHtml.basePath();

        // Serve compiled widget plugins under {path}/plugins/** before the SPA deep-link handler.
        if (widgetPluginScanner != null) {
            registry.addResourceHandler(base + "/plugins/**")
                    .addResourceLocations(widgetPluginScanner.serveLocation())
                    .resourceChain(true);
        }

        // Spring strips the /assets/ pattern prefix before resolving the remaining path, so point
        // this handler at the emitted assets directory itself. Resolving against static/ui/ looks
        // for static/ui/<hash>.js and leaves every packaged SPA blank (#339).
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/ui/assets/")
                .resourceChain(true);

        // Exact root files retain their full filename and therefore resolve from the bundle root.
        // Keep both handlers file-only so missing files and API routes never become the SPA shell.
        registry.addResourceHandler("/favicon.svg", "/manifest.webmanifest")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true);

        String[] navigationPatterns = base.isEmpty()
                ? new String[]{"/**"}
                : new String[]{base, base + "/", base + "/**"};
        var navigation = registry.addResourceHandler(navigationPatterns);
        if (base.isEmpty()) {
            navigation.addResourceLocations("classpath:/META-INF/resources/", "classpath:/resources/",
                    "classpath:/static/", "classpath:/public/", "classpath:/static/ui/");
        } else {
            navigation.addResourceLocations("classpath:/static/ui/");
        }
        navigation
                .resourceChain(true)
                .addResolver(new SpaResourceResolver(spaIndexHtml, base));
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
    public BatchRunner batchRunner(UiProperties uiProperties) {
        return new BatchRunner(uiProperties.getBatch().getParallelism());
    }

    @Bean
    public ActionController actionController(CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             UiAccessService access,
                                             UiActionResolver uiActionResolver,
                                             UiProperties uiProperties,
                                             BatchRunner batchRunner) {
        return new ActionController(catalogQueryService, documentQueryService, access, uiActionResolver,
                uiProperties, batchRunner);
    }

    @Bean
    public ProcessController processController(
            su.onno.process.ProcessEngine processEngine,
            su.onno.process.ProcessDefinitions processDefinitions,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            UiAccessService access,
            TaskAssigneeDirectory taskAssigneeDirectory,
            CurrentUserResolver currentUserResolver,
            RecordAccess recordAccess,
            AccessSubjectResolver accessSubjectResolver) {
        ProcessController controller = new ProcessController(
                processEngine, processDefinitions, objectMapper, access,
                taskAssigneeDirectory, currentUserResolver);
        controller.setRecordAccess(recordAccess, accessSubjectResolver);
        return controller;
    }

    @Bean
    public TaskAssigneeDirectory taskAssigneeDirectory(
            su.onno.metadata.MetadataRegistry registry,
            CatalogQueryService catalogQueryService,
            UiAccessService access,
            su.onno.ui.UiLayout uiLayout,
            CommentAuthorAvatars commentAuthorAvatars,
            AccessSubjectResolver accessSubjectResolver) {
        return new TaskAssigneeDirectory(
                registry, catalogQueryService, access, uiLayout, commentAuthorAvatars, accessSubjectResolver);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public OnnoValidationExceptionHandler onnoValidationExceptionHandler() {
        return new OnnoValidationExceptionHandler();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public ActionRejectedExceptionHandler actionRejectedExceptionHandler() {
        return new ActionRejectedExceptionHandler();
    }

    @Bean
    public ThemeController themeController(UiProperties properties, su.onno.ui.UiLayout uiLayout,
                                          UiMessages uiMessages,
                                          org.springframework.beans.factory.ObjectProvider<UpdateChecker> updateChecker,
                                          org.springframework.beans.factory.ObjectProvider<WidgetPluginScanner> widgetPlugins,
                                          org.springframework.beans.factory.ObjectProvider<
                                                  su.onno.observability.TelemetrySink> telemetrySink) {
        return new ThemeController(properties, uiLayout, uiMessages, updateChecker, widgetPlugins, telemetrySink);
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
    public UiAccessService uiAccessService(MetadataRegistry registry, org.springframework.beans.factory.ObjectProvider<UiEntityAccessPolicy> policies) {
        return new UiAccessService(registry, () -> policies.orderedStream().toList());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public UiEventPublisher uiEventPublisher(UiAccessService access, UiProperties properties,
                                             RecordScopeCompiler recordScopeCompiler, Jdbi jdbi,
                                             MetadataRegistry registry) {
        // onno.ui.dev-mode unset → dev mode exactly when devtools is around: on the classpath under
        // bootRun/exploded runs, excluded from the production boot jar. See UiProperties#devMode.
        boolean devMode = properties.getDevMode() != null
                ? properties.getDevMode()
                : org.springframework.util.ClassUtils.isPresent(
                        "org.springframework.boot.devtools.settings.DevToolsSettings",
                        UiAutoConfiguration.class.getClassLoader());
        UiEventPublisher publisher = new UiEventPublisher(access, devMode);
        publisher.setRecordScopes(recordScopeCompiler, jdbi, registry);
        return publisher;
    }

    @Bean
    public UiEventController uiEventController(UiEventPublisher publisher, UiAccessService access,
                                              CurrentUserResolver currentUserResolver) {
        return new UiEventController(publisher, access, currentUserResolver);
    }

    @Bean(destroyMethod = "stop")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public DevReloadTrigger devReloadTrigger(UiEventPublisher publisher, UiProperties properties) {
        // Inert outside dev mode — the watcher thread only exists on a live-development server.
        return publisher.isDevMode()
                ? new DevReloadTrigger(java.nio.file.Path.of(properties.getDevReloadTrigger()), publisher)
                : DevReloadTrigger.disabled();
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
                                                                     CommentAuthorAvatars authorAvatars,
                                                                     RecordAccess recordAccess) {
        return new su.onno.ui.presence.PresenceController(presenceRegistry, access, currentUserResolver, authorAvatars,
                recordAccess);
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

    /** The validated record policies; boot fails here on an invalid {@code RecordAccessPolicy}. */
    @Bean
    public su.onno.access.RecordPolicies recordPolicies(
            MetadataRegistry registry,
            org.springframework.beans.factory.ObjectProvider<su.onno.access.RecordAccessPolicy> policies) {
        return new su.onno.access.RecordPolicies(registry, policies.orderedStream().toList());
    }

    @Bean
    public RecordScopeCompiler recordScopeCompiler(su.onno.access.RecordPolicies recordPolicies) {
        return new RecordScopeCompiler(recordPolicies);
    }

    @Bean
    public RecordAccess recordAccess(RecordScopeCompiler recordScopeCompiler, UiAccessService access, Jdbi jdbi,
                                     RecordAccessProperties properties) {
        return new RecordAccess(recordScopeCompiler, access, jdbi, properties.isLogDenials());
    }

    @Bean
    public RecordScopeIndexes recordScopeIndexes(su.onno.access.RecordPolicies recordPolicies, Jdbi jdbi,
                                                 RecordAccessProperties properties,
                                                 org.springframework.core.env.Environment environment) {
        return new RecordScopeIndexes(recordPolicies, jdbi, properties.isCreateIndexes(),
                environment.getProperty("onno.schema.mode", "update"));
    }

    @Bean
    public AccessSubjectResolver accessSubjectResolver(
            UiAccessService access, CurrentUserResolver currentUserResolver,
            org.springframework.beans.factory.ObjectProvider<su.onno.access.AccessSubjectContributor> contributors) {
        return new AccessSubjectResolver(access, currentUserResolver, () -> contributors.orderedStream().toList());
    }

    @Bean
    public CatalogQueryService catalogQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                   RecordScopeCompiler recordScopeCompiler, UiAccessService access) {
        return new CatalogQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public DocumentQueryService documentQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                     RecordScopeCompiler recordScopeCompiler, UiAccessService access) {
        return new DocumentQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public RefOptionService refOptionService(
            org.springframework.beans.factory.ObjectProvider<RefOptionDecorator> decorators) {
        return new RefOptionService(decorators.orderedStream().toList());
    }

    @Bean
    public RefOptionController refOptionController(CatalogQueryService catalogQueryService,
                                                   DocumentQueryService documentQueryService,
                                                   UiAccessService access,
                                                   RefOptionService refOptionService) {
        return new RefOptionController(catalogQueryService, documentQueryService, access, refOptionService);
    }

    @Bean
    public FormValidationService formValidationService(
            org.springframework.beans.factory.ObjectProvider<FormValidator> validators) {
        return new FormValidationService(validators.orderedStream().toList());
    }

    @Bean
    public FormValidationController formValidationController(CatalogQueryService catalogQueryService,
                                                             DocumentQueryService documentQueryService,
                                                             UiAccessService access,
                                                             FieldHintResolver fieldHintResolver,
                                                             FormValidationService validationService) {
        return new FormValidationController(catalogQueryService, documentQueryService, access,
                fieldHintResolver, validationService);
    }

    @Bean
    public RegisterQueryService registerQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                     RecordScopeCompiler recordScopeCompiler, UiAccessService access) {
        return new RegisterQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public InformationRegisterQueryService informationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                                           RecordScopeCompiler recordScopeCompiler,
                                                                           UiAccessService access) {
        return new InformationRegisterQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public RelatedListReader relatedListReader(FieldHintResolver fieldHintResolver, MetadataRegistry registry,
                                               CatalogQueryService catalogQueryService,
                                               InformationRegisterQueryService informationRegisterQueryService,
                                               UiAccessService access, RecordAccess recordAccess) {
        return new RelatedListReader(fieldHintResolver, registry, catalogQueryService,
                informationRegisterQueryService, access, recordAccess);
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
                                                              UiMessages uiMessages,
                                                              BatchRunner batchRunner) {
        return new GenericCatalogController(catalogQueryService, access, catalogCommandService,
                relatedListReader, uiMessages, batchRunner);
    }

    @Bean
    public GenericDocumentController genericDocumentController(DocumentQueryService documentQueryService,
                                                                UiAccessService access,
                                                                DocumentCommandService documentCommandService,
                                                                RelatedListReader relatedListReader,
                                                                BatchRunner batchRunner) {
        return new GenericDocumentController(documentQueryService, access, documentCommandService,
                relatedListReader, batchRunner);
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
                properties.getList().getPageSize());
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
                                             org.springframework.beans.factory.ObjectProvider<su.onno.ui.notifications.NotificationProperties> notificationProperties,
                                             AccessSubjectResolver accessSubjectResolver) {
        DivKitController controller = new DivKitController(layoutSet, layoutResolver, profileResolver, access,
                currentUserResolver, resolvedMetadata, uiViewResolver, pageResolver, catalogQueryService,
                documentQueryService, registerQueryService, uiActionResolver, relatedListReader, uiProperties,
                uiMessages, commentProperties, notificationProperties);
        controller.setAccessSubjectResolver(accessSubjectResolver);
        return controller;
    }

}
