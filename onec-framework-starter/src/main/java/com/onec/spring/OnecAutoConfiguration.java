package com.onec.spring;

import com.onec.metadata.*;
import com.onec.posting.PostingEngine;
import com.onec.posting.PostingService;
import com.onec.ui.*;
import com.onec.posting.RegisterPersistence;
import com.onec.jobs.BackgroundJobs;
import com.onec.messaging.OutboxWriter;
import com.onec.repository.*;

import org.jdbi.v3.core.Jdbi;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.repository.config.BootstrapMode;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(OnecProperties.class)
@ConditionalOnBean(DataSource.class)
public class OnecAutoConfiguration extends AbstractJdbcConfiguration {

    @org.springframework.beans.factory.annotation.Autowired
    private OnecProperties oneCProperties;

    @org.springframework.beans.factory.annotation.Autowired
    private ApplicationContext bootstrapContext;

    @Bean
    public NamingStrategy oneCNamingStrategy() {
        return new OnecNamingStrategy(buildTabularSectionTables(resolvePackages(oneCProperties, bootstrapContext)));
    }

    /**
     * Maps each tabular-section row class to the child table the schema generator creates
     * ({@code document_<doc>_<section>}), so {@link OnecNamingStrategy} can give Spring Data JDBC
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
        if (oneCProperties != null && oneCProperties.getScanPackages() != null && !oneCProperties.getScanPackages().isEmpty()) {
            packages = oneCProperties.getScanPackages();
        } else if (bootstrapContext != null) {
            packages = AutoConfigurationPackages.get(bootstrapContext);
        } else {
            System.err.println("[onec] userConverters() called before context wired; falling back to system property");
            String fallback = System.getProperty("onec.scan-packages");
            packages = fallback == null ? java.util.List.of() : java.util.List.of(fallback.split(","));
        }
        System.err.println("[onec] userConverters(): scanning packages=" + packages);
        if (packages == null || packages.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<EnumerationDescriptor> enums = new java.util.ArrayList<>();
        MetadataScanner mdScanner = new MetadataScanner(new DefaultNamingStrategy());
        for (Class<?> clazz : new EnumerationScanner().scan(packages)) {
            enums.add(mdScanner.scanEnumeration(clazz));
        }
        System.err.println("[onec] userConverters(): registered converters for " + enums.size() + " enums");
        return EnumUuidConverters.build(enums);
    }

    @Override
    public JdbcMappingContext jdbcMappingContext(java.util.Optional<NamingStrategy> namingStrategy,
                                                 org.springframework.data.jdbc.core.convert.JdbcCustomConversions customConversions,
                                                 org.springframework.data.relational.RelationalManagedTypes jdbcManagedTypes) {
        OnecMappingContext context = new OnecMappingContext(namingStrategy.orElseGet(OnecNamingStrategy::new));
        context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
        return context;
    }

    /**
     * Replaces the default {@code JdbcConverter} with {@link OnecJdbcConverter}, which maps framework
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
        return new OnecJdbcConverter(mappingContext, relationResolver, conversions, jdbcTypeFactory);
    }

    @Bean
    public SchemaInitializer schemaInitializer(DataSource dataSource, OnecProperties properties,
                                               ApplicationContext context) {
        return new SchemaInitializer(dataSource, resolvePackages(properties, context));
    }

    @Bean
    public com.onec.security.SecretCipher secretCipher(OnecProperties properties) {
        return new com.onec.security.SecretCipher(properties.getSecurity().getSecretKey());
    }

    @Bean
    public OnecBeforeConvertCallback oneCBeforeConvertCallback(MetadataRegistry registry,
                                                                com.onec.numbering.NumberGenerator numberGenerator,
                                                                com.onec.security.SecretCipher secretCipher) {
        return new OnecBeforeConvertCallback(registry, numberGenerator, secretCipher);
    }

    /**
     * Bridges the framework's {@link com.onec.events.EntityChangePublisher} SPI onto Spring's
     * {@link org.springframework.context.ApplicationEventPublisher}, so a {@code repository.save}/
     * {@code delete} publishes an {@link com.onec.events.EntityChangedEvent} that application code can
     * {@code @EventListener} — the same event the generic controllers emit, so both write paths are
     * observable through one hook (issues #28, #29).
     */
    @Bean
    public com.onec.events.EntityChangePublisher entityChangePublisher(
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
        return applicationEventPublisher::publishEvent;
    }

    @Bean
    public OnecAfterSaveCallback oneCAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                                       com.onec.security.SecretCipher secretCipher,
                                                       com.onec.events.EntityChangePublisher entityChangePublisher) {
        return new OnecAfterSaveCallback(outboxWriter, registry, secretCipher, entityChangePublisher);
    }

    @Bean
    public OnecAfterConvertCallback oneCAfterConvertCallback(MetadataRegistry registry,
                                                             com.onec.security.SecretCipher secretCipher) {
        return new OnecAfterConvertCallback(registry, secretCipher);
    }

    @Bean
    public OnecBeforeDeleteCallback oneCBeforeDeleteCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                                             com.onec.events.EntityChangePublisher entityChangePublisher) {
        return new OnecBeforeDeleteCallback(outboxWriter, registry, entityChangePublisher);
    }

    @Bean
    public com.onec.numbering.NumberGenerator numberGenerator(Jdbi jdbi) {
        return new JdbcNumberGenerator(jdbi);
    }

    @Bean
    public com.onec.types.RefResolver refResolver(ApplicationContext applicationContext) {
        return new SpringRefResolver(applicationContext);
    }

    @Bean
    public MetadataRegistry metadataRegistry(OnecProperties properties, ApplicationContext context) {
        return buildRegistry(resolvePackages(properties, context));
    }

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        return Jdbi.create(dataSource);
    }

    /**
     * The unified type-safe query layer: a {@link com.onec.query.QueryEngine} over
     * catalogs, documents, and registers with {@code Ref}-navigation joins. Apps inject
     * it to run {@code query.from(...).select(...).where(...).fetch()} queries.
     */
    @Bean
    public com.onec.query.QueryEngine queryEngine(Jdbi jdbi, MetadataRegistry metadataRegistry) {
        return new com.onec.query.QueryEngine(jdbi, metadataRegistry);
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
     * Bridges the framework's {@link com.onec.posting.PostEventPublisher} SPI onto Spring's
     * {@link org.springframework.context.ApplicationEventPublisher}, so a successful post/unpost
     * publishes a {@link com.onec.posting.DocumentPostedEvent}/
     * {@link com.onec.posting.DocumentUnpostedEvent} that application code can {@code @EventListener}.
     * This is the Spring-bean-reachable "after post" hook (no Kafka outbox required).
     */
    @Bean
    public com.onec.posting.PostEventPublisher postEventPublisher(
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
        return applicationEventPublisher::publishEvent;
    }

    @Bean
    public PostingService postingService(Jdbi jdbi, MetadataRegistry registry,
                                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryImplMap,
                                         OutboxWriter outboxWriter,
                                         com.onec.posting.PostEventPublisher postEventPublisher) {
        PostingEngine engine = new PostingEngine(jdbi, registry, repositoryImplMap, outboxWriter, postEventPublisher);
        return new PostingService(engine);
    }

    @Bean
    public EnumerationPersistence enumerationPersistence(Jdbi jdbi) {
        return new EnumerationPersistence(jdbi);
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
    public LayoutSet layoutSet(MetadataRegistry registry, List<Layout> layouts) {
        Map<Viewport, UiLayout> byViewport = new java.util.EnumMap<>(Viewport.class);
        for (Viewport v : Viewport.values()) {
            List<Layout> chosen = selectForViewport(layouts, v);
            // Auto-place un-placed entities only when this viewport uses the universal
            // default layout. A device-specific default is a deliberate curation — don't
            // dump the rest of the metadata into its nav.
            boolean curated = chosen.stream()
                    .anyMatch(l -> profileKey(l).isEmpty() && l.viewport() == v);
            byViewport.put(v, buildUiLayout(registry, chosen, !curated));
        }
        return new LayoutSet(byViewport);
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

    private UiLayout buildUiLayout(MetadataRegistry registry, List<Layout> layouts, boolean applyFallback) {
        // Each Layout bean is one persona's shell; the default layout (profile()==null)
        // is the back-office shell. A Layout IS a profile.
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

        // Collect classes the default layout already placed explicitly
        Set<Class<?>> placed = defaultSections.stream()
                .flatMap(s -> s.entityRefs().stream())
                .map(UiLayoutBuilder.EntityRef::javaClass)
                .collect(Collectors.toSet());

        // Auto-place remaining entities using default fallback grouping.
        // Explicit placement is the job of Layout beans (the source of truth).
        UiLayoutBuilder fallbackBuilder = new UiLayoutBuilder();

        if (applyFallback) {
            for (CatalogDescriptor d : registry.allCatalogs()) {
                if (placed.contains(d.javaClass())) continue;
                fallbackBuilder.section("Catalogs").order(900).catalog(d.javaClass());
            }
            for (DocumentDescriptor d : registry.allDocuments()) {
                if (placed.contains(d.javaClass())) continue;
                fallbackBuilder.section("Documents").order(901).document(d.javaClass());
            }
            for (AccumulationRegisterDescriptor d : registry.allRegisters()) {
                if (placed.contains(d.javaClass())) continue;
                fallbackBuilder.section("Registers").order(902).register(d.javaClass());
            }
        }

        // Merge: explicit default sections first, then fallback sections
        List<UiLayout.Section> merged = new ArrayList<>(defaultSections);
        Set<String> explicitNames = defaultSections.stream()
                .map(UiLayout.Section::name)
                .collect(Collectors.toSet());
        for (UiLayout.Section fb : fallbackBuilder.build()) {
            if (!explicitNames.contains(fb.name())) {
                merged.add(fb);
            }
        }
        merged.sort(Comparator.comparingInt(UiLayout.Section::order));

        // Dashboard widgets now live in Page beans, not the layout.
        return new UiLayout(merged, List.of(), profiles, identity, shell);
    }

    @Bean
    public UiLayoutResolver uiLayoutResolver(MetadataRegistry registry) {
        return new UiLayoutResolver(registry);
    }

    @Bean
    public com.onec.ui.UiProfileResolver uiProfileResolver() {
        return new com.onec.ui.UiProfileResolver();
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

    static List<String> resolvePackages(OnecProperties properties, ApplicationContext context) {
        if (!properties.getScanPackages().isEmpty()) {
            return properties.getScanPackages();
        }
        if (AutoConfigurationPackages.has(context)) {
            return AutoConfigurationPackages.get(context);
        }
        throw new IllegalStateException(
                "Could not determine base packages for 1C entity scanning. " +
                "Either use @SpringBootApplication or set onec.scan-packages in your configuration.");
    }
}
