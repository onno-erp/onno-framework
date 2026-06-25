package su.onno.spring;

import su.onno.metadata.*;
import su.onno.posting.PostingEngine;
import su.onno.posting.PostingService;
import su.onno.ui.*;
import su.onno.posting.RegisterPersistence;
import su.onno.jobs.BackgroundJobs;
import su.onno.messaging.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import su.onno.repository.*;

import org.jdbi.v3.core.Jdbi;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.repository.config.BootstrapMode;

import javax.sql.DataSource;
import java.util.*;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(OnnoProperties.class)
@ConditionalOnBean(DataSource.class)
public class OnnoAutoConfiguration extends AbstractJdbcConfiguration {

    @org.springframework.beans.factory.annotation.Autowired
    private OnnoProperties onnoProperties;

    @org.springframework.beans.factory.annotation.Autowired
    private ApplicationContext bootstrapContext;

    @Bean
    public NamingStrategy onnoNamingStrategy() {
        return new OnnoNamingStrategy(buildTabularSectionTables(resolvePackages(onnoProperties, bootstrapContext)));
    }

    /**
     * Maps each tabular-section row class to the child table the schema generator creates
     * ({@code document_<doc>_<section>}), so {@link OnnoNamingStrategy} can give Spring Data JDBC
     * the same table name when it persists a document's {@code @TabularSection List<Row>}.
     */
    static Map<Class<?>, String> buildTabularSectionTables(List<String> scanPackages) {
        Map<Class<?>, String> map = new HashMap<>();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        for (Class<?> clazz : new DocumentScanner().scan(scanPackages)) {
            DocumentDescriptor doc = scanner.scanDocument(clazz);
            for (TabularSectionDescriptor ts : doc.tabularSections()) {
                map.put(ts.rowClass(), ts.tableName());
            }
        }
        return map;
    }

    @Override
    protected java.util.List<?> userConverters() {
        java.util.List<String> packages;
        if (onnoProperties != null && onnoProperties.getScanPackages() != null && !onnoProperties.getScanPackages().isEmpty()) {
            packages = onnoProperties.getScanPackages();
        } else if (bootstrapContext != null) {
            packages = AutoConfigurationPackages.get(bootstrapContext);
        } else {
            System.err.println("[onno] userConverters() called before context wired; falling back to system property");
            String fallback = System.getProperty("onno.scan-packages");
            packages = fallback == null ? java.util.List.of() : java.util.List.of(fallback.split(","));
        }
        System.err.println("[onno] userConverters(): scanning packages=" + packages);
        if (packages == null || packages.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<EnumerationDescriptor> enums = new java.util.ArrayList<>();
        MetadataScanner mdScanner = new MetadataScanner(new DefaultNamingStrategy());
        for (Class<?> clazz : new EnumerationScanner().scan(packages)) {
            enums.add(mdScanner.scanEnumeration(clazz));
        }
        System.err.println("[onno] userConverters(): registered converters for " + enums.size() + " enums");
        return EnumUuidConverters.build(enums);
    }

    @Override
    public JdbcMappingContext jdbcMappingContext(java.util.Optional<NamingStrategy> namingStrategy,
                                                 org.springframework.data.jdbc.core.convert.JdbcCustomConversions customConversions,
                                                 org.springframework.data.relational.RelationalManagedTypes jdbcManagedTypes) {
        OnnoMappingContext context = new OnnoMappingContext(namingStrategy.orElseGet(OnnoNamingStrategy::new));
        context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
        return context;
    }

    /**
     * Replaces the default {@code JdbcConverter} with {@link OnnoJdbcConverter}, which maps framework
     * enums to their {@code UUID} column type so {@code @Enumeration} attributes persist through
     * {@code repository.save(...)} (issue #26). The body mirrors
     * {@link AbstractJdbcConfiguration#jdbcConverter} so all other behaviour is unchanged.
     */
    @Override
    public org.springframework.data.jdbc.core.convert.JdbcConverter jdbcConverter(
            JdbcMappingContext mappingContext,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations operations,
            @org.springframework.context.annotation.Lazy org.springframework.data.jdbc.core.convert.RelationResolver relationResolver,
            org.springframework.data.jdbc.core.convert.JdbcCustomConversions conversions,
            org.springframework.data.relational.core.dialect.Dialect dialect) {
        org.springframework.data.jdbc.core.convert.JdbcArrayColumns arrayColumns =
                dialect instanceof org.springframework.data.jdbc.core.dialect.JdbcDialect jdbcDialect
                        ? jdbcDialect.getArraySupport()
                        : org.springframework.data.jdbc.core.convert.JdbcArrayColumns.DefaultSupport.INSTANCE;
        org.springframework.data.jdbc.core.convert.DefaultJdbcTypeFactory jdbcTypeFactory =
                new org.springframework.data.jdbc.core.convert.DefaultJdbcTypeFactory(
                        operations.getJdbcOperations(), arrayColumns);
        return new OnnoJdbcConverter(mappingContext, relationResolver, conversions, jdbcTypeFactory);
    }

    @Bean
    public SchemaInitializer schemaInitializer(DataSource dataSource, OnnoProperties properties,
                                               ApplicationContext context,
                                               ObjectProvider<su.onno.migration.AppMigration> migrations) {
        return new SchemaInitializer(dataSource, resolvePackages(properties, context),
                su.onno.schema.SchemaMode.fromString(properties.getSchema().getMode()),
                properties.getSchema().isAllowDestructive(),
                migrations.orderedStream().toList());
    }

    @Bean
    public OnnoMetrics onnoMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new OnnoMetrics(meterRegistry.getIfAvailable());
    }

    @Bean
    public su.onno.security.SecretCipher secretCipher(OnnoProperties properties) {
        return new su.onno.security.SecretCipher(properties.getSecurity().getSecretKey());
    }

    /**
     * Boot-time guardrail that flags catalog/document repository finders which may return soft-deleted
     * rows into business logic (see {@link DeletionAwareFinderValidator}). Controlled by
     * {@code onno.repository.deletion-check} ({@code warn} default / {@code strict} / {@code off}).
     */
    @Bean
    public DeletionAwareFinderValidator deletionAwareFinderValidator(ApplicationContext context,
                                                                     OnnoProperties properties) {
        return new DeletionAwareFinderValidator(context,
                DeletionAwareFinderValidator.Mode.fromString(properties.getRepository().getDeletionCheck()));
    }

    @Bean
    public OnnoBeforeConvertCallback onnoBeforeConvertCallback(MetadataRegistry registry,
                                                                su.onno.numbering.NumberGenerator numberGenerator,
                                                                su.onno.security.SecretCipher secretCipher,
                                                                OnnoMetrics metrics) {
        return new OnnoBeforeConvertCallback(registry, numberGenerator, secretCipher, metrics);
    }

    /**
     * Bridges the framework's {@link su.onno.events.EntityChangePublisher} SPI onto Spring's
     * {@link org.springframework.context.ApplicationEventPublisher}, so a {@code repository.save}/
     * {@code delete} publishes an {@link su.onno.events.EntityChangedEvent} that application code can
     * {@code @EventListener} — the same event the generic controllers emit, so both write paths are
     * observable through one hook (issues #28, #29).
     */
    @Bean
    public su.onno.events.EntityChangePublisher entityChangePublisher(
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
        return applicationEventPublisher::publishEvent;
    }

    /**
     * Local-only fallback {@link su.onno.cluster.ClusterEventBus}. {@code onno-cluster-starter}
     * supplies a Postgres {@code LISTEN}/{@code NOTIFY} bus (its auto-configuration is ordered
     * {@code before} this one, so its {@code @ConditionalOnMissingBean} bean registers first and this
     * one backs off); an application may override either with its own bean. With this no-op default,
     * the cluster relay/bridge are inert and single-node behaviour is unchanged.
     */
    @Bean
    @ConditionalOnMissingBean(su.onno.cluster.ClusterEventBus.class)
    public su.onno.cluster.ClusterEventBus clusterEventBus() {
        return new su.onno.cluster.NoOpClusterEventBus();
    }

    /**
     * Publish side of cross-node live-UI sync: relays each {@link su.onno.events.EntityChangedEvent}
     * onto the {@link su.onno.cluster.ClusterEventBus} for peer nodes (no-op with the default bus).
     */
    @Bean
    public ClusterEntityChangeRelay clusterEntityChangeRelay(su.onno.cluster.ClusterEventBus clusterEventBus) {
        return new ClusterEntityChangeRelay(clusterEventBus);
    }

    @Bean
    public OnnoAfterSaveCallback onnoAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                                       su.onno.security.SecretCipher secretCipher,
                                                       su.onno.events.EntityChangePublisher entityChangePublisher,
                                                       OnnoMetrics metrics) {
        return new OnnoAfterSaveCallback(outboxWriter, registry, secretCipher, entityChangePublisher, metrics);
    }

    @Bean
    public OnnoAfterConvertCallback onnoAfterConvertCallback(MetadataRegistry registry,
                                                             su.onno.security.SecretCipher secretCipher) {
        return new OnnoAfterConvertCallback(registry, secretCipher);
    }

    @Bean
    public OnnoBeforeDeleteCallback onnoBeforeDeleteCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                                             su.onno.events.EntityChangePublisher entityChangePublisher) {
        return new OnnoBeforeDeleteCallback(outboxWriter, registry, entityChangePublisher);
    }

    @Bean
    public su.onno.numbering.NumberGenerator numberGenerator(Jdbi jdbi) {
        return new JdbcNumberGenerator(jdbi);
    }

    @Bean
    public su.onno.types.RefResolver refResolver(ApplicationContext applicationContext) {
        return new SpringRefResolver(applicationContext);
    }

    @Bean
    public MetadataRegistry metadataRegistry(OnnoProperties properties, ApplicationContext context) {
        return buildRegistry(resolvePackages(properties, context));
    }

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        return Jdbi.create(dataSource);
    }

    /**
     * The unified type-safe query layer: a {@link su.onno.query.QueryEngine} over
     * catalogs, documents, and registers with {@code Ref}-navigation joins. Apps inject
     * it to run {@code query.from(...).select(...).where(...).fetch()} queries.
     */
    @Bean
    public su.onno.query.QueryEngine queryEngine(Jdbi jdbi, MetadataRegistry metadataRegistry) {
        return new su.onno.query.QueryEngine(jdbi, metadataRegistry);
    }

    @Bean
    public OutboxWriter outboxWriter(Jdbi jdbi) {
        return new OutboxWriter(jdbi);
    }

    @Bean
    public Map<Class<?>, RegisterPersistence<?>> registerPersistenceMap(Jdbi jdbi, MetadataRegistry registry) {
        return buildRegisterPersistenceMap(jdbi, registry);
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<Class<?>, RegisterRepositoryImpl<?>> registerRepositoryImplMap(
            Map<Class<?>, RegisterPersistence<?>> persistenceMap) {
        Map<Class<?>, RegisterRepositoryImpl<?>> repos = new HashMap<>();
        for (var entry : persistenceMap.entrySet()) {
            Class registerClass = entry.getKey();
            repos.put(entry.getKey(), new RegisterRepositoryImpl(entry.getValue(), registerClass));
        }
        return repos;
    }

    /**
     * Bridges the framework's {@link su.onno.posting.PostEventPublisher} SPI onto Spring's
     * {@link org.springframework.context.ApplicationEventPublisher}, so a successful post/unpost
     * publishes a {@link su.onno.posting.DocumentPostedEvent}/
     * {@link su.onno.posting.DocumentUnpostedEvent} that application code can {@code @EventListener}.
     * This is the Spring-bean-reachable "after post" hook (no Kafka outbox required).
     */
    @Bean
    public su.onno.posting.PostEventPublisher postEventPublisher(
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
        return applicationEventPublisher::publishEvent;
    }

    @Bean
    public PostingService postingService(Jdbi jdbi, MetadataRegistry registry,
                                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryImplMap,
                                         OutboxWriter outboxWriter,
                                         su.onno.posting.PostEventPublisher postEventPublisher,
                                         OnnoMetrics metrics) {
        PostingEngine engine = new PostingEngine(
                jdbi, registry, repositoryImplMap, outboxWriter, postEventPublisher);
        return new TimedPostingService(engine, metrics);
    }

    @Bean
    public Map<Class<?>, InformationRegisterRepositoryImpl<?>> informationRegisterRepositoryMap(
            Jdbi jdbi, MetadataRegistry registry) {
        Map<Class<?>, InformationRegisterRepositoryImpl<?>> repos = new HashMap<>();
        for (InformationRegisterDescriptor desc : registry.allInformationRegisters()) {
            var persistence = new InformationRegisterPersistence<>(jdbi, desc);
            repos.put(desc.javaClass(), new InformationRegisterRepositoryImpl<>(persistence));
        }
        return repos;
    }

    @Bean
    public ConstantPersistence constantPersistence(Jdbi jdbi) {
        return new ConstantPersistence(jdbi);
    }

    @Bean
    public ConstantManager constantManager(ConstantPersistence constantPersistence, MetadataRegistry registry) {
        return new ConstantManager(constantPersistence, registry);
    }

    @Bean
    @ConditionalOnBean(JobScheduler.class)
    public BackgroundJobs backgroundJobs(JobScheduler jobScheduler) {
        return new JobrunrBackgroundJobs(jobScheduler);
    }

    @Bean
    @ConditionalOnBean(JobScheduler.class)
    public ScheduledJobRegistrar scheduledJobRegistrar(ApplicationContext applicationContext,
                                                        JobScheduler jobScheduler) {
        return new ScheduledJobRegistrar(applicationContext, jobScheduler);
    }

    @Bean
    public LayoutSet layoutSet(List<Layout> layouts) {
        Map<Viewport, UiLayout> byViewport = new java.util.EnumMap<>(Viewport.class);
        java.util.Set<Viewport> dedicated = java.util.EnumSet.noneOf(Viewport.class);
        for (Viewport v : Viewport.values()) {
            List<Layout> chosen = selectForViewport(layouts, v);
            // Whether a default layout specifically targets this viewport — record it so the shell
            // only treats v as having its own nav (vs. inheriting the universal one) when that's true.
            boolean curated = chosen.stream()
                    .anyMatch(l -> profileKey(l).isEmpty() && l.viewport() == v);
            if (curated) {
                dedicated.add(v);
            }
            byViewport.put(v, buildUiLayout(chosen));
        }
        return new LayoutSet(byViewport, dedicated);
    }

    /**
     * The default ({@link Viewport#DESKTOP}) layout, kept as a bean for consumers
     * that aren't viewport-aware (identity resolution, the metadata API).
     */
    @Bean
    public UiLayout uiLayout(LayoutSet layoutSet) {
        return layoutSet.forViewport(Viewport.DESKTOP);
    }

    /**
     * Choose one {@link Layout} per profile for this viewport: a viewport-specific
     * layout wins over the universal ({@code viewport()==null}) one.
     */
    private static List<Layout> selectForViewport(List<Layout> layouts, Viewport viewport) {
        Map<String, Layout> chosen = new LinkedHashMap<>();
        for (Layout l : layouts) {
            if (l.viewport() == null) {
                chosen.putIfAbsent(profileKey(l), l);
            }
        }
        for (Layout l : layouts) {
            if (l.viewport() == viewport) {
                chosen.put(profileKey(l), l);
            }
        }
        return new ArrayList<>(chosen.values());
    }

    private static String profileKey(Layout l) {
        return l.profile() == null ? "" : l.profile();
    }

    private UiLayout buildUiLayout(List<Layout> layouts) {
        // Each Layout bean is one persona's shell; the default layout (profile()==null)
        // is the back-office shell. A Layout IS a profile.
        //
        // Nav is opt-in: an entity appears in the sidebar only if a Layout bean explicitly
        // places it in a (non-HIDDEN) section. There is no auto-listing of un-placed
        // catalogs/documents/registers — declaring an entity registers it (so it stays
        // routable and usable as a ref) without dumping it into the nav. Layout beans are
        // the single source of truth for what the sidebar shows.
        List<UiLayout.Section> defaultSections = new ArrayList<>();
        List<UiLayout.Profile> profiles = new ArrayList<>();
        ShellConfig shell = ShellConfig.defaults();
        UiIdentityLink identity = null;

        for (Layout layout : layouts) {
            LayoutSpec spec = new LayoutSpec();
            layout.configure(spec);
            if (layout.profile() == null) {
                defaultSections.addAll(spec.sections());
                shell = spec.shellConfig();
                if (spec.identity() != null) {
                    identity = spec.identity();
                }
            } else {
                profiles.add(new UiLayout.Profile(layout.profile(), spec.title(), spec.theme(),
                        spec.roles(), spec.priority(), spec.sections(), List.of()));
            }
        }

        List<UiLayout.Section> merged = new ArrayList<>(defaultSections);
        merged.sort(Comparator.comparingInt(UiLayout.Section::order));

        // Dashboard widgets now live in Page beans, not the layout.
        return new UiLayout(merged, List.of(), profiles, identity, shell);
    }

    @Bean
    public UiLayoutResolver uiLayoutResolver(MetadataRegistry registry) {
        return new UiLayoutResolver(registry);
    }

    @Bean
    public su.onno.ui.UiProfileResolver uiProfileResolver() {
        return new su.onno.ui.UiProfileResolver();
    }

    private MetadataRegistry buildRegistry(List<String> scanPackages) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        for (Class<?> clazz : new CatalogScanner().scan(scanPackages)) {
            registry.registerCatalog(scanner.scan(clazz));
            registry.registerDashboardWidgets(scanner.scanDashboardWidgets(clazz));
        }
        for (Class<?> clazz : new DocumentScanner().scan(scanPackages)) {
            registry.registerDocument(scanner.scanDocument(clazz));
            registry.registerDashboardWidgets(scanner.scanDashboardWidgets(clazz));
        }
        for (Class<?> clazz : new AccumulationScanner().scan(scanPackages)) {
            registry.registerAccumulation(scanner.scanRegister(clazz));
            registry.registerDashboardWidgets(scanner.scanDashboardWidgets(clazz));
        }
        for (Class<?> clazz : new EnumerationScanner().scan(scanPackages)) {
            registry.registerEnumeration(scanner.scanEnumeration(clazz));
        }
        for (Class<?> clazz : new InformationRegisterScanner().scan(scanPackages)) {
            registry.registerInformationRegister(scanner.scanInformationRegister(clazz));
        }
        for (Class<?> clazz : new ConstantScanner().scan(scanPackages)) {
            registry.registerConstant(scanner.scanConstant(clazz));
        }

        return registry;
    }

    private Map<Class<?>, RegisterPersistence<?>> buildRegisterPersistenceMap(
            Jdbi jdbi, MetadataRegistry registry) {
        Map<Class<?>, RegisterPersistence<?>> map = new HashMap<>();
        for (AccumulationRegisterDescriptor desc : registry.allRegisters()) {
            map.put(desc.javaClass(), new RegisterPersistence<>(jdbi, desc));
        }
        return map;
    }

    static List<String> resolvePackages(OnnoProperties properties, ApplicationContext context) {
        if (!properties.getScanPackages().isEmpty()) {
            return properties.getScanPackages();
        }
        if (AutoConfigurationPackages.has(context)) {
            return AutoConfigurationPackages.get(context);
        }
        throw new IllegalStateException(
                "Could not determine base packages for 1C entity scanning. " +
                "Either use @SpringBootApplication or set onno.scan-packages in your configuration.");
    }
}
