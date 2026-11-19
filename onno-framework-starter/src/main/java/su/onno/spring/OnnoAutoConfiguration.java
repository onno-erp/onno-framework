package su.onno.spring;

import su.onno.metadata.*;
import su.onno.posting.PostingEngine;
import su.onno.posting.PostingService;
import su.onno.posting.RegisterPersistence;
import su.onno.jobs.BackgroundJobs;
import su.onno.messaging.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import su.onno.repository.*;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.spring5.SpringConnectionFactory;
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
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.lang.NonNull;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
     * ({@code document_<doc>_<section>} or {@code catalog_<catalog>_<section>}), so {@link OnnoNamingStrategy} can give Spring Data JDBC
     * the same table name when it persists a catalog/document's {@code @TabularSection List<Row>}. Rejects
     * a concrete row class owned by more than one section because a class can map to only one table.
     */
    static Map<Class<?>, String> buildTabularSectionTables(List<String> scanPackages) {
        Map<Class<?>, String> map = new LinkedHashMap<>();
        Map<Class<?>, TabularSectionOwner> owners = new HashMap<>();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        List<Class<?>> classes = new ArrayList<>(new DocumentScanner().scan(scanPackages));
        classes.addAll(new CatalogScanner().scan(scanPackages));
        for (Class<?> clazz : classes) {
            List<TabularSectionDescriptor> sections = su.onno.model.CatalogObject.class.isAssignableFrom(clazz)
                    ? scanner.scan(clazz).tabularSections() : scanner.scanDocument(clazz).tabularSections();
            for (TabularSectionDescriptor ts : sections) {
                TabularSectionOwner owner = new TabularSectionOwner(
                        clazz, ts.fieldName(), ts.name(), ts.tableName());
                TabularSectionOwner existing = owners.putIfAbsent(ts.rowClass(), owner);
                if (existing != null && !existing.equals(owner)) {
                    throw new IllegalStateException(
                            "Tabular section row class " + ts.rowClass().getName()
                                    + " is reused by " + existing.describe() + " and " + owner.describe()
                                    + ". Each @TabularSection must use a distinct concrete row class"
                                    + " because Spring Data JDBC maps one row class to one child table."
                                    + " Create one concrete row class per section; share fields or"
                                    + " behavior through a common base class.");
                }
                map.putIfAbsent(ts.rowClass(), ts.tableName());
            }
        }
        return map;
    }

    private record TabularSectionOwner(
            Class<?> documentClass, String fieldName, String sectionName, String tableName) {

        String describe() {
            return documentClass.getName() + "#" + fieldName
                    + " (@TabularSection(name=\"" + sectionName + "\"), table=\"" + tableName + "\")";
        }
    }

    @Override
    @NonNull
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
    @NonNull
    public JdbcMappingContext jdbcMappingContext(@NonNull java.util.Optional<NamingStrategy> namingStrategy,
                                                 @NonNull org.springframework.data.jdbc.core.convert.JdbcCustomConversions customConversions,
                                                 @NonNull org.springframework.data.relational.RelationalManagedTypes jdbcManagedTypes) {
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
    @NonNull
    public org.springframework.data.jdbc.core.convert.JdbcConverter jdbcConverter(
            @NonNull JdbcMappingContext mappingContext,
            @NonNull org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations operations,
            @NonNull @org.springframework.context.annotation.Lazy org.springframework.data.jdbc.core.convert.RelationResolver relationResolver,
            @NonNull org.springframework.data.jdbc.core.convert.JdbcCustomConversions conversions,
            @NonNull org.springframework.data.relational.core.dialect.Dialect dialect) {
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
                su.onno.schema.SearchIndexMode.fromString(properties.getSchema().getSearchIndexes()),
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
                                                                OnnoMetrics metrics,
                                                                org.springframework.beans.factory.ObjectProvider<su.onno.period.PeriodCloseService> periodClose) {
        OnnoBeforeConvertCallback callback =
                new OnnoBeforeConvertCallback(registry, numberGenerator, secretCipher, metrics);
        callback.setPeriodCloseService(periodClose.getIfAvailable());
        return callback;
    }

    /**
     * Period close ("change-prohibition date"). Owns the {@code onno_period_close} and
     * {@code onno_period_close_audit} tables, created here when missing. Disable with
     * {@code onno.period-close.enabled=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "onno.period-close", name = "enabled", havingValue = "true", matchIfMissing = true)
    public su.onno.period.PeriodCloseService periodCloseService(
            Jdbi jdbi, MetadataRegistry registry,
            org.springframework.beans.factory.ObjectProvider<su.onno.period.PeriodCloseOverridePolicy> overridePolicy) {
        su.onno.period.PeriodCloseService service = new su.onno.period.PeriodCloseService(jdbi, registry);
        service.ensureSchema();
        service.setOverridePolicy(overridePolicy.getIfAvailable());
        return service;
    }

    /**
     * Bridges the framework's {@link su.onno.events.EntityChangePublisher} SPI onto Spring's
     * {@link org.springframework.context.ApplicationEventPublisher}, so a {@code repository.save}/
     * {@code delete} publishes an {@link su.onno.events.EntityChangedEvent} that application code can
     * {@code @EventListener} — the same event the generic controllers emit, so both write paths are
     * observable through one hook (issues #28, #29). Publication waits for an active Spring
     * transaction to commit; rollback discards the event. Without a transaction it is immediate.
     */
    @Bean
    public su.onno.events.EntityChangePublisher entityChangePublisher(
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
        return new TransactionalEntityChangePublisher(applicationEventPublisher);
    }

    /** Bridges durable process mutations onto Spring after their database transaction commits. */
    @Bean
    public su.onno.process.ProcessEventPublisher processEventPublisher(
            org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
        return applicationEventPublisher::publishEvent;
    }

    /**
     * Local-only fallback {@link su.onno.cluster.ClusterEventBus}. {@code OnnoClusterAutoConfiguration}
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

    /** Relays audience-scoped process-task inbox invalidations to peer nodes. */
    @Bean
    public ClusterProcessTaskRelay clusterProcessTaskRelay(
            su.onno.cluster.ClusterEventBus clusterEventBus) {
        return new ClusterProcessTaskRelay(clusterEventBus);
    }

    @Bean
    public OnnoAfterSaveCallback onnoAfterSaveCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                                       su.onno.security.SecretCipher secretCipher,
                                                       su.onno.events.EntityChangePublisher entityChangePublisher,
                                                       OnnoMetrics metrics) {
        return new OnnoAfterSaveCallback(outboxWriter, registry, secretCipher, entityChangePublisher, metrics);
    }

    /**
     * The record change history (field-level audit log) behind {@code @Catalog(history = true)} /
     * {@code @Document(history = true)}. With {@code onno.history.enabled=false} it is the inert
     * {@link su.onno.history.RecordHistory#disabled()} recorder, so collaborators never branch.
     */
    @Bean
    @ConditionalOnMissingBean(su.onno.history.RecordHistory.class)
    public su.onno.history.RecordHistory recordHistory(
            Jdbi jdbi, MetadataRegistry registry, su.onno.security.SecretCipher secretCipher,
            OnnoProperties properties,
            org.springframework.beans.factory.ObjectProvider<su.onno.history.HistoryActorResolver> actorResolver) {
        if (!properties.getHistory().isEnabled()) {
            return su.onno.history.RecordHistory.disabled();
        }
        return new su.onno.history.RecordHistory(jdbi, registry, secretCipher, () -> {
            su.onno.history.HistoryActorResolver resolver = actorResolver.getIfAvailable();
            return resolver == null ? null : resolver.resolve();
        });
    }

    /** Records history for typed repository saves/deletes of opted-in entities. */
    @Bean
    public RecordHistoryCallback recordHistoryCallback(su.onno.history.RecordHistory recordHistory, Jdbi jdbi) {
        return new RecordHistoryCallback(recordHistory, jdbi);
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
    public OnnoAfterDeleteCallback onnoAfterDeleteCallback(OutboxWriter outboxWriter, MetadataRegistry registry,
                                                           su.onno.events.EntityChangePublisher entityChangePublisher) {
        return new OnnoAfterDeleteCallback(outboxWriter, registry, entityChangePublisher);
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
        return Jdbi.create(new SpringConnectionFactory(dataSource));
    }

    /** Validates and indexes every application-provided typed process definition. */
    @Bean
    public su.onno.process.ProcessDefinitions processDefinitions(
            ObjectProvider<su.onno.process.ProcessDefinition<?, ?>> definitions,
            ObjectProvider<su.onno.process.ProcessDefinitionMigration<?, ?, ?, ?>> migrations) {
        return new su.onno.process.ProcessDefinitions(
                definitions.orderedStream().toList(),
                migrations.orderedStream().toList());
    }

    /** Application-configured Jackson boundary used by the core durable process runtime. */
    @Bean
    @ConditionalOnMissingBean(su.onno.process.ProcessPayloadCodec.class)
    public su.onno.process.ProcessPayloadCodec processPayloadCodec(
            ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> objectMapper) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = objectMapper.getIfAvailable(
                () -> new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        return new su.onno.process.JacksonProcessPayloadCodec(mapper);
    }

    /** Durable transactional process runtime backed by the framework-managed process tables. */
    @Bean
    @ConditionalOnMissingBean(su.onno.process.ProcessEngine.class)
    public su.onno.process.ProcessEngine processEngine(
            Jdbi jdbi,
            su.onno.process.ProcessDefinitions definitions,
            MetadataRegistry metadataRegistry,
            su.onno.process.ProcessPayloadCodec payloadCodec,
            su.onno.process.ProcessEventPublisher events) {
        return new su.onno.process.JdbcProcessEngine(
                jdbi, definitions, metadataRegistry, payloadCodec, events);
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
    @ConditionalOnMissingBean(su.onno.posting.PostedDocumentLoader.class)
    public su.onno.posting.PostedDocumentLoader postedDocumentLoader(
            MetadataRegistry registry, ApplicationContext context) {
        return new SpringPostedDocumentLoader(registry, context);
    }

    @Bean
    public PostingService postingService(Jdbi jdbi, MetadataRegistry registry,
                                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryImplMap,
                                         OutboxWriter outboxWriter,
                                         su.onno.posting.PostEventPublisher postEventPublisher,
                                         su.onno.posting.PostedDocumentLoader postedDocumentLoader,
                                         OnnoMetrics metrics,
                                         org.springframework.beans.factory.ObjectProvider<su.onno.period.PeriodCloseService> periodClose,
                                         su.onno.history.RecordHistory recordHistory) {
        PostingEngine engine = new PostingEngine(
                jdbi, registry, repositoryImplMap, outboxWriter, postEventPublisher,
                postedDocumentLoader);
        engine.setPeriodCloseService(periodClose.getIfAvailable());
        engine.setHistory(recordHistory);
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
    public ScheduledJobRegistrar scheduledJobRegistrar(ApplicationContext applicationContext,
                                                        ObjectProvider<JobScheduler> jobScheduler,
                                                        OnnoProperties properties) {
        return new ScheduledJobRegistrar(applicationContext, jobScheduler.getIfAvailable(),
                resolvePackages(properties, applicationContext));
    }

    /** Durable wake-up for due process timers and completed subprocess waits. */
    @Bean
    @ConditionalOnBean(JobScheduler.class)
    public ProcessPendingWorkRegistrar processPendingWorkRegistrar(
            su.onno.process.ProcessEngine processEngine,
            JobScheduler jobScheduler) {
        return new ProcessPendingWorkRegistrar(processEngine, jobScheduler);
    }

    private MetadataRegistry buildRegistry(List<String> scanPackages) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        for (Class<?> clazz : new CatalogScanner().scan(scanPackages)) {
            registry.registerCatalog(scanner.scan(clazz));
        }
        for (Class<?> clazz : new DocumentScanner().scan(scanPackages)) {
            registry.registerDocument(scanner.scanDocument(clazz));
        }
        for (Class<?> clazz : new AccumulationScanner().scan(scanPackages)) {
            registry.registerAccumulation(scanner.scanRegister(clazz));
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

    /** The commands every {@code Commands} bean declares — what any surface (REST, MCP, …) offers and runs. */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public su.onno.command.CommandRegistry commandRegistry(ObjectProvider<su.onno.command.Commands> commands) {
        return new su.onno.command.CommandRegistry(commands.orderedStream().toList());
    }

    /** The live checks every {@code Checks} bean declares. */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public su.onno.validation.CheckRegistry checkRegistry(ObjectProvider<su.onno.validation.Checks> checks) {
        return new su.onno.validation.CheckRegistry(checks.orderedStream().toList());
    }

    /** The picker options every {@code PickerOptions} bean binds to a reference field. */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public su.onno.picker.PickerOptionsService pickerOptionsService(MetadataRegistry registry,
            ObjectProvider<su.onno.picker.PickerOptions> pickerOptions) {
        return new su.onno.picker.PickerOptionsService(registry, pickerOptions.orderedStream().toList());
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
