package su.onno.web;

import su.onno.picker.PickerOptionsService;
import su.onno.validation.CheckRegistry;
import su.onno.command.CommandRegistry;
import su.onno.access.AccessSubject;
import su.onno.metadata.MetadataRegistry;
import su.onno.numbering.NumberGenerator;
import su.onno.posting.PostingService;
import su.onno.spring.OnnoAutoConfiguration;
import su.onno.spring.OnnoProperties;
import su.onno.web.comments.CommentAuthorAvatars;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = OnnoAutoConfiguration.class)
@EnableConfigurationProperties({WebProperties.class, UiProperties.class, UpdateProperties.class,
        RecordAccessProperties.class})
@ConditionalOnBean(MetadataRegistry.class)
@ConditionalOnProperty(prefix = "onno.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UiAutoConfiguration implements WebMvcConfigurer {

    // The SPA shell with onno.ui.path baked in — shared by the deep-link fallback resolver and the
    // root controller so both serve a shell that knows the app's mount prefix.
    private final SpaIndexHtml spaIndexHtml;

    public UiAutoConfiguration(UiProperties uiProperties) {
        this.spaIndexHtml = new SpaIndexHtml(uiProperties.getPath());
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
        // An application's own UI build (classpath:/onno-app/) is listed before the default shell
        // (classpath:/static/ui/) everywhere, so an app that ships one is served instead. Vite
        // asset names are content-hashed, so the two bundles' assets never collide.
        String app = "classpath:/" + SpaIndexHtml.APP_LOCATION;
        String shell = "classpath:/" + SpaIndexHtml.DEFAULT_LOCATION;

        // Spring strips the /assets/ pattern prefix before resolving the remaining path, so point
        // this handler at the emitted assets directories themselves (#339).
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(app + "assets/", shell + "assets/")
                .resourceChain(true);

        // Exact root files retain their full filename and therefore resolve from the bundle root.
        // Keep both handlers file-only so missing files and API routes never become the SPA shell.
        registry.addResourceHandler("/favicon.svg", "/manifest.webmanifest")
                .addResourceLocations(app, shell)
                .resourceChain(true);

        String[] navigationPatterns = base.isEmpty()
                ? new String[]{"/**"}
                : new String[]{base, base + "/", base + "/**"};
        var navigation = registry.addResourceHandler(navigationPatterns);
        if (base.isEmpty()) {
            navigation.addResourceLocations("classpath:/META-INF/resources/", "classpath:/resources/",
                    "classpath:/static/", "classpath:/public/", app, shell);
        } else {
            navigation.addResourceLocations(app, shell);
        }
        navigation
                .resourceChain(true)
                .addResolver(new SpaResourceResolver(spaIndexHtml, base));
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
                                                AccessService access) {
        return new SettingsController(registry, constantManager, access);
    }

    @Bean
    public ListDataController listDataController(CatalogQueryService catalogQueryService,
                                                DocumentQueryService documentQueryService,
                                                AccessService access,
                                                CommandRegistry commandRegistry) {
        return new ListDataController(catalogQueryService, documentQueryService, access, commandRegistry);
    }

    @Bean
    public BatchRunner batchRunner(WebProperties webProperties) {
        return new BatchRunner(webProperties.getBatch().getParallelism());
    }

    /** The one command execution path shared by REST, MCP and module endpoints. */
    @Bean
    public CommandService commandService(CatalogQueryService catalogQueryService,
                                         DocumentQueryService documentQueryService,
                                         AccessService access,
                                         CommandRegistry commandRegistry,
                                         WebProperties webProperties,
                                         BatchRunner batchRunner) {
        return new CommandService(catalogQueryService, documentQueryService, access, commandRegistry,
                webProperties, batchRunner);
    }

    @Bean
    public CommandController commandController(CommandService commandService) {
        return new CommandController(commandService);
    }

    @Bean
    public ProcessController processController(
            su.onno.process.ProcessEngine processEngine,
            su.onno.process.ProcessDefinitions processDefinitions,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            AccessService access,
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
            AccessService access,
            IdentityBinding identityBinding,
            CommentAuthorAvatars commentAuthorAvatars,
            AccessSubjectResolver accessSubjectResolver) {
        return new TaskAssigneeDirectory(
                registry, catalogQueryService, access, identityBinding, commentAuthorAvatars, accessSubjectResolver);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public OnnoValidationExceptionHandler onnoValidationExceptionHandler() {
        return new OnnoValidationExceptionHandler();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public CommandRejectedExceptionHandler commandRejectedExceptionHandler() {
        return new CommandRejectedExceptionHandler();
    }

    @Bean
    public UiConfigController uiConfigController(UiProperties properties,
                                          WebProperties webProperties,
                                          UiMessages uiMessages,
                                          org.springframework.beans.factory.ObjectProvider<UpdateChecker> updateChecker) {
        return new UiConfigController(properties, webProperties, uiMessages, updateChecker);
    }

    /**
     * Polls onno-cloud for a newer framework release and exposes the result through {@code /config}.
     * Disabled with {@code onno.web.update-check.enabled=false}; otherwise on by default and fail-silent.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "onno.web.update-check", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public UpdateChecker updateChecker(UpdateProperties updateProperties,
                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new UpdateChecker(updateProperties, OnnoBuildInfo.version(), objectMapper);
    }

    @Bean
    public UiLoginController uiLoginController(
            org.springframework.beans.factory.ObjectProvider<su.onno.auth.spi.AuthMethodsProvider> authMethods,
            org.springframework.beans.factory.ObjectProvider<su.onno.auth.spi.AuthMethodsContributor> contributors,
            UiMessages uiMessages,
            UiProperties properties) {
        return new UiLoginController(authMethods, contributors, uiMessages, properties);
    }

    @Bean
    public AccessService uiAccessService(MetadataRegistry registry, org.springframework.beans.factory.ObjectProvider<EntityAccessPolicy> policies) {
        return new AccessService(registry, () -> policies.orderedStream().toList());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public EventPublisher uiEventPublisher(AccessService access, WebProperties properties,
                                             RecordScopeCompiler recordScopeCompiler, Jdbi jdbi,
                                             MetadataRegistry registry) {
        // onno.web.dev-mode unset → dev mode exactly when devtools is around: on the classpath under
        // bootRun/exploded runs, excluded from the production boot jar. See WebProperties#devMode.
        boolean devMode = properties.getDevMode() != null
                ? properties.getDevMode()
                : org.springframework.util.ClassUtils.isPresent(
                        "org.springframework.boot.devtools.settings.DevToolsSettings",
                        UiAutoConfiguration.class.getClassLoader());
        EventPublisher publisher = new EventPublisher(access, devMode);
        publisher.setRecordScopes(recordScopeCompiler, jdbi, registry);
        return publisher;
    }

    @Bean
    public EventController uiEventController(EventPublisher publisher, AccessService access,
                                              CurrentUserResolver currentUserResolver) {
        return new EventController(publisher, access, currentUserResolver);
    }

    @Bean(destroyMethod = "stop")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public DevReloadTrigger devReloadTrigger(EventPublisher publisher, WebProperties properties) {
        // Inert outside dev mode — the watcher thread only exists on a live-development server.
        return publisher.isDevMode()
                ? new DevReloadTrigger(java.nio.file.Path.of(properties.getDevReloadTrigger()), publisher)
                : DevReloadTrigger.disabled();
    }

    /**
     * Bridges the cross-node {@link su.onno.cluster.ClusterEventBus} into the local SSE stream so a
     * write on one node lights up browsers connected to any node. With the default no-op bus this is
     * inert. See {@link ClusterEventBridge} for why received events bypass the Spring event bus.
     */
    @Bean
    public ClusterEventBridge clusterEventBridge(su.onno.cluster.ClusterEventBus clusterEventBus,
                                           EventPublisher publisher) {
        return new ClusterEventBridge(clusterEventBus, publisher);
    }

    /**
     * Tracks who is viewing each record for record-level collaboration markers. It subscribes to the
     * {@link su.onno.cluster.ClusterEventBus} for peer presence and pushes viewer-set changes onto the SSE
     * stream. With the default no-op bus it is a single-node, in-memory registry.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.web.presence.PresenceRegistry presenceRegistry(su.onno.cluster.ClusterEventBus clusterEventBus,
                                                                 EventPublisher publisher) {
        return new su.onno.web.presence.PresenceRegistry(clusterEventBus, publisher);
    }

    @Bean
    public su.onno.web.presence.PresenceController presenceController(su.onno.web.presence.PresenceRegistry presenceRegistry,
                                                                     AccessService access,
                                                                     CurrentUserResolver currentUserResolver,
                                                                     CommentAuthorAvatars authorAvatars,
                                                                     RecordAccess recordAccess) {
        return new su.onno.web.presence.PresenceController(presenceRegistry, access, currentUserResolver, authorAvatars,
                recordAccess);
    }

    /**
     * Resolves a user's avatar image URL from the identity catalog's avatar/image-hinted column.
     * Always-on (not gated on {@code onno.comments.enabled}) so both the comments panel and record-level
     * presence markers render a viewer's photo from the one identity source.
     */
    @Bean
    public CommentAuthorAvatars commentAuthorAvatars(IdentityBinding identityBinding, MetadataRegistry registry, Jdbi jdbi) {
        return new CommentAuthorAvatars(identityBinding, registry, jdbi);
    }

    @Bean
    public ResolvedMetadataService resolvedMetadataService(MetadataRegistry registry,
                                                           CheckRegistry checkRegistry) {
        return new ResolvedMetadataService(registry, checkRegistry);
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
    public RecordAccess recordAccess(RecordScopeCompiler recordScopeCompiler, AccessService access, Jdbi jdbi,
                                     RecordAccessProperties properties) {
        return new RecordAccess(recordScopeCompiler, access, jdbi, properties.isLogDenials());
    }

    @Bean
    public RecordScopeIndexes recordScopeIndexes(su.onno.access.RecordPolicies recordPolicies, Jdbi jdbi,
                                                 RecordAccessProperties properties,
                                                 OnnoProperties onnoProperties) {
        // The typed property, not the Environment: its "apply" default lives on the field (#460).
        return new RecordScopeIndexes(recordPolicies, jdbi, properties.isCreateIndexes(),
                onnoProperties.getSchema().getMode());
    }

    @Bean
    public AccessSubjectResolver accessSubjectResolver(
            AccessService access, CurrentUserResolver currentUserResolver,
            org.springframework.beans.factory.ObjectProvider<su.onno.access.AccessSubjectContributor> contributors) {
        return new AccessSubjectResolver(access, currentUserResolver, () -> contributors.orderedStream().toList());
    }

    @Bean
    public CatalogQueryService catalogQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                   RecordScopeCompiler recordScopeCompiler, AccessService access) {
        return new CatalogQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public DocumentQueryService documentQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                     RecordScopeCompiler recordScopeCompiler, AccessService access) {
        return new DocumentQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    /** Per-user recently picked ref values, offered first by the ref picker (#409). */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public RefRecentStore refRecentStore(Jdbi jdbi) {
        return new RefRecentStore(jdbi);
    }

    @Bean
    public RefOptionController refOptionController(CatalogQueryService catalogQueryService,
                                                   DocumentQueryService documentQueryService,
                                                   AccessService access,
                                                   PickerOptionsService pickerOptionsService,
                                                   RefRecentStore refRecentStore) {
        return new RefOptionController(catalogQueryService, documentQueryService, access, pickerOptionsService,
                refRecentStore);
    }

    @Bean
    public CheckController checkController(CatalogQueryService catalogQueryService,
                                           DocumentQueryService documentQueryService,
                                           AccessService access,
                                           CheckRegistry checkRegistry) {
        return new CheckController(catalogQueryService, documentQueryService, access, checkRegistry);
    }

    @Bean
    public RegisterQueryService registerQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                     RecordScopeCompiler recordScopeCompiler, AccessService access) {
        return new RegisterQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public InformationRegisterQueryService informationRegisterQueryService(MetadataRegistry registry, Jdbi jdbi,
                                                                           RecordScopeCompiler recordScopeCompiler,
                                                                           AccessService access) {
        return new InformationRegisterQueryService(registry, jdbi, recordScopeCompiler, access);
    }

    @Bean
    public CatalogCommandService catalogCommandService(MetadataRegistry registry, Jdbi jdbi,
                                                       WebProperties properties,
                                                       NumberGenerator numberGenerator,
                                                       CatalogQueryService catalogQueryService,
                                                       AccessService access,
                                                       org.springframework.context.ApplicationEventPublisher events,
                                                       su.onno.security.SecretCipher secretCipher,
                                                       org.springframework.beans.factory.ObjectProvider<su.onno.history.RecordHistory> recordHistory) {
        CatalogCommandService service = new CatalogCommandService(registry, jdbi, properties, numberGenerator,
                catalogQueryService, access, events, secretCipher);
        service.setHistory(recordHistory.getIfAvailable());
        return service;
    }

    @Bean
    public DocumentCommandService documentCommandService(MetadataRegistry registry, Jdbi jdbi,
                                                         WebProperties properties,
                                                         NumberGenerator numberGenerator,
                                                         PostingService postingService,
                                                         DocumentQueryService documentQueryService,
                                                         AccessService access,
                                                         org.springframework.context.ApplicationEventPublisher events,
                                                         su.onno.security.SecretCipher secretCipher,
                                                         org.springframework.beans.factory.ObjectProvider<su.onno.period.PeriodCloseService> periodClose,
                                                         org.springframework.beans.factory.ObjectProvider<su.onno.history.RecordHistory> recordHistory) {
        DocumentCommandService service = new DocumentCommandService(registry, jdbi, properties,
                numberGenerator, postingService, documentQueryService, access, events, secretCipher);
        service.setPeriodCloseService(periodClose.getIfAvailable());
        service.setHistory(recordHistory.getIfAvailable());
        return service;
    }

    /** Period-close roles ({@code onno.period-close.manage-roles} / {@code override-roles}) and override policy. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.web.period.PeriodCloseAccess periodCloseAccess(
            AccessService access,
            org.springframework.beans.factory.ObjectProvider<su.onno.spring.OnnoProperties> onnoProperties) {
        su.onno.spring.OnnoProperties onno = onnoProperties.getIfAvailable();
        return new su.onno.web.period.PeriodCloseAccess(access,
                onno == null ? java.util.List.<String>of() : onno.getPeriodClose().getManageRoles(),
                onno == null ? java.util.List.<String>of() : onno.getPeriodClose().getOverrideRoles());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.web.period.PeriodClosedExceptionHandler periodClosedExceptionHandler() {
        return new su.onno.web.period.PeriodClosedExceptionHandler();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(su.onno.period.PeriodCloseService.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.web.period.PeriodCloseController periodCloseController(
            su.onno.period.PeriodCloseService periods, su.onno.web.period.PeriodCloseAccess periodAccess,
            AccessService access, WebProperties properties) {
        return new su.onno.web.period.PeriodCloseController(periods, periodAccess, access, properties);
    }

    /**
     * Attributes record-history rows written outside a generic command (typed repository saves,
     * {@code PostingService} calls from application code) to the request's authenticated user and
     * their {@code Layout.identity(...)} record. Off a request thread it resolves nothing and the
     * change is recorded as {@code system}.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public su.onno.history.HistoryActorResolver historyActorResolver(AccessService access,
                                                                    CurrentUserResolver currentUserResolver) {
        return () -> {
            String username = access.username(null);
            if (username == null || username.isBlank()) {
                return null;
            }
            String recordId = currentUserResolver.resolve(() -> username).recordId();
            return new su.onno.history.HistoryActor(username,
                    recordId == null || recordId.isBlank() ? null : java.util.UUID.fromString(recordId));
        };
    }

    /** The {@code /api/history} read endpoint; 404s for entities that have not opted in. */
    @Bean
    @ConditionalOnBean(su.onno.history.RecordHistory.class)
    public su.onno.web.history.RecordHistoryController recordHistoryController(
            su.onno.history.RecordHistory recordHistory, AccessService access,
            CatalogQueryService catalogQueryService, DocumentQueryService documentQueryService,
            UiMessages uiMessages) {
        return new su.onno.web.history.RecordHistoryController(recordHistory, access, catalogQueryService,
                documentQueryService, uiMessages);
    }

    @Bean
    public GenericCatalogController genericCatalogController(CatalogQueryService catalogQueryService,
                                                              AccessService access,
                                                              CatalogCommandService catalogCommandService,
                                                              UiMessages uiMessages,
                                                              BatchRunner batchRunner) {
        return new GenericCatalogController(catalogQueryService, access, catalogCommandService,
                uiMessages, batchRunner);
    }

    @Bean
    public GenericDocumentController genericDocumentController(DocumentQueryService documentQueryService,
                                                                AccessService access,
                                                                DocumentCommandService documentCommandService,
                                                                BatchRunner batchRunner) {
        return new GenericDocumentController(documentQueryService, access, documentCommandService,
                batchRunner);
    }

    @Bean
    public GenericInformationRegisterController genericInformationRegisterController(
            InformationRegisterQueryService query, AccessService access) {
        return new GenericInformationRegisterController(query, access);
    }

    @Bean
    public GenericRegisterController genericRegisterController(RegisterQueryService registerQueryService,
                                                               AccessService access) {
        return new GenericRegisterController(registerQueryService, access);
    }

    @Bean
    public RegisterListController registerListController(RegisterQueryService registerQueryService,
                                                         AccessService access, UiMessages uiMessages) {
        return new RegisterListController(registerQueryService, access, uiMessages);
    }

    @Bean
    public CurrentUserResolver currentUserResolver(IdentityBinding identityBinding,
                                                   MetadataRegistry registry, Jdbi jdbi) {
        return new CurrentUserResolver(identityBinding, registry, jdbi);
    }

    @Bean
    public DefaultListViews defaultListViews(ResolvedMetadataService resolvedMetadata,
                                         WebProperties properties) {
        return new DefaultListViews(resolvedMetadata, properties.getList().getPageSize());
    }

    /**
     * The app's identity binding — which catalog record a login is — from an optional
     * {@code @Bean IdentityLink} (e.g. {@code IdentityLink.of(Employee.class, "email")}).
     */
    @Bean
    public IdentityBinding identityBinding(org.springframework.beans.factory.ObjectProvider<su.onno.identity.IdentityLink> link) {
        return new IdentityBinding(link.getIfAvailable());
    }

    @Bean
    public UiViewController uiViewController(MetadataRegistry registry,
                                             AccessService access,
                                             CurrentUserResolver currentUserResolver,
                                             ResolvedMetadataService resolvedMetadata,
                                             DefaultListViews defaultListViews,
                                             CatalogQueryService catalogQueryService,
                                             DocumentQueryService documentQueryService,
                                             RegisterQueryService registerQueryService,
                                             CommandService commandService,
                                             WebProperties webProperties,
                                             UiMessages uiMessages,
                                             org.springframework.beans.factory.ObjectProvider<su.onno.web.comments.CommentProperties> commentProperties,
                                             org.springframework.beans.factory.ObjectProvider<su.onno.web.notifications.NotificationProperties> notificationProperties,
                                             AccessSubjectResolver accessSubjectResolver,
                                             org.springframework.beans.factory.ObjectProvider<RecordActionContributor> recordActionContributors,
                                             org.springframework.beans.factory.ObjectProvider<SelectionDownloadContributor> selectionDownloadContributors,
                                             org.springframework.beans.factory.ObjectProvider<su.onno.history.RecordHistory> recordHistory,
                                             org.springframework.beans.factory.ObjectProvider<su.onno.period.PeriodCloseService> periodClose) {
        UiViewController controller = new UiViewController(registry, access,
                currentUserResolver, resolvedMetadata, defaultListViews, catalogQueryService,
                documentQueryService, registerQueryService, commandService, webProperties,
                uiMessages, commentProperties, notificationProperties);
        controller.setAccessSubjectResolver(accessSubjectResolver);
        controller.setRecordActionContributors(recordActionContributors.orderedStream().toList());
        controller.setSelectionDownloadContributors(selectionDownloadContributors.orderedStream().toList());
        controller.setRecordHistory(recordHistory.getIfAvailable());
        controller.setPeriodCloseEnabled(periodClose.getIfAvailable() != null);
        return controller;
    }

}
