package com.onec.spring;

import com.onec.annotations.UiSection;
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
@EnableConfigurationProperties(OneCProperties.class)
@ConditionalOnBean(DataSource.class)
public class OneCAutoConfiguration extends AbstractJdbcConfiguration {

    @org.springframework.beans.factory.annotation.Autowired
    private OneCProperties oneCProperties;

    @org.springframework.beans.factory.annotation.Autowired
    private ApplicationContext bootstrapContext;

    @Bean
    public NamingStrategy oneCNamingStrategy() {
        return new OneCNamingStrategy();
    }

    @Override
    protected java.util.List<?> userConverters() {
        java.util.List<String> packages;
        if (oneCProperties != null && oneCProperties.getScanPackages() != null && !oneCProperties.getScanPackages().isEmpty()) {
            packages = oneCProperties.getScanPackages();
        } else if (bootstrapContext != null) {
            packages = AutoConfigurationPackages.get(bootstrapContext);
        } else {
            System.err.println("[OneC] userConverters() called before context wired; falling back to system property");
            String fallback = System.getProperty("onec.scan-packages");
            packages = fallback == null ? java.util.List.of() : java.util.List.of(fallback.split(","));
        }
        System.err.println("[OneC] userConverters(): scanning packages=" + packages);
        if (packages == null || packages.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<EnumerationDescriptor> enums = new java.util.ArrayList<>();
        MetadataScanner mdScanner = new MetadataScanner(new DefaultNamingStrategy());
        for (Class<?> clazz : new EnumerationScanner().scan(packages)) {
            enums.add(mdScanner.scanEnumeration(clazz));
        }
        System.err.println("[OneC] userConverters(): registered converters for " + enums.size() + " enums");
        return EnumUuidConverters.build(enums);
    }

    @Override
    public JdbcMappingContext jdbcMappingContext(java.util.Optional<NamingStrategy> namingStrategy,
                                                 org.springframework.data.jdbc.core.convert.JdbcCustomConversions customConversions,
                                                 org.springframework.data.relational.RelationalManagedTypes jdbcManagedTypes) {
        OneCMappingContext context = new OneCMappingContext(namingStrategy.orElseGet(OneCNamingStrategy::new));
        context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
        return context;
    }

    @Bean
    public SchemaInitializer schemaInitializer(DataSource dataSource, OneCProperties properties,
                                               ApplicationContext context) {
        return new SchemaInitializer(dataSource, resolvePackages(properties, context));
    }

    @Bean
    public OneCBeforeConvertCallback oneCBeforeConvertCallback(MetadataRegistry registry,
                                                                com.onec.numbering.NumberGenerator numberGenerator) {
        return new OneCBeforeConvertCallback(registry, numberGenerator);
    }

    @Bean
    public OneCAfterSaveCallback oneCAfterSaveCallback(OutboxWriter outboxWriter) {
        return new OneCAfterSaveCallback(outboxWriter);
    }

    @Bean
    public OneCBeforeDeleteCallback oneCBeforeDeleteCallback(OutboxWriter outboxWriter) {
        return new OneCBeforeDeleteCallback(outboxWriter);
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
    public MetadataRegistry metadataRegistry(OneCProperties properties, ApplicationContext context) {
        return buildRegistry(resolvePackages(properties, context));
    }

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        return Jdbi.create(dataSource);
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

    @Bean
    public PostingService postingService(Jdbi jdbi, MetadataRegistry registry,
                                         Map<Class<?>, RegisterRepositoryImpl<?>> repositoryImplMap,
                                         OutboxWriter outboxWriter) {
        PostingEngine engine = new PostingEngine(jdbi, registry, repositoryImplMap, outboxWriter);
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
    public UiLayout uiLayout(MetadataRegistry registry,
                              List<OneCUiConfigurer> configurers) {
        UiLayoutBuilder builder = new UiLayoutBuilder();

        for (OneCUiConfigurer configurer : configurers) {
            configurer.configure(builder);
        }

        UiLayout explicit = new UiLayout(builder.build());

        // Collect classes that the user already placed explicitly
        Set<Class<?>> placed = explicit.sections().stream()
                .flatMap(s -> s.entityRefs().stream())
                .map(UiLayoutBuilder.EntityRef::javaClass)
                .collect(Collectors.toSet());

        // Auto-place remaining entities using @UiSection or fallback grouping
        UiLayoutBuilder fallbackBuilder = new UiLayoutBuilder();

        for (CatalogDescriptor d : registry.allCatalogs()) {
            if (placed.contains(d.javaClass())) continue;
            UiSection s = d.javaClass().getAnnotation(UiSection.class);
            String section = s != null ? s.value() : "Catalogs";
            int order = s != null ? s.order() : 900;
            fallbackBuilder.section(section).order(order).catalog(d.javaClass());
        }
        for (DocumentDescriptor d : registry.allDocuments()) {
            if (placed.contains(d.javaClass())) continue;
            UiSection s = d.javaClass().getAnnotation(UiSection.class);
            String section = s != null ? s.value() : "Documents";
            int order = s != null ? s.order() : 901;
            fallbackBuilder.section(section).order(order).document(d.javaClass());
        }
        for (AccumulationRegisterDescriptor d : registry.allRegisters()) {
            if (placed.contains(d.javaClass())) continue;
            UiSection s = d.javaClass().getAnnotation(UiSection.class);
            String section = s != null ? s.value() : "Registers";
            int order = s != null ? s.order() : 902;
            fallbackBuilder.section(section).order(order).register(d.javaClass());
        }

        // Merge: explicit sections first, then fallback sections
        List<UiLayout.Section> merged = new ArrayList<>(explicit.sections());
        Set<String> explicitNames = explicit.sections().stream()
                .map(UiLayout.Section::name)
                .collect(Collectors.toSet());
        for (UiLayout.Section fb : fallbackBuilder.build()) {
            if (!explicitNames.contains(fb.name())) {
                merged.add(fb);
            }
        }
        merged.sort(Comparator.comparingInt(UiLayout.Section::order));

        // Widgets: use explicit config if provided, otherwise fall back to annotations
        List<UiLayoutBuilder.WidgetConfig> widgets;
        if (builder.hasWidgets()) {
            widgets = builder.buildWidgets();
        } else {
            widgets = List.of(); // will fall back to annotation-based widgets in registry
        }

        return new UiLayout(merged, widgets, builder.buildProfiles(), builder.buildIdentity());
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

    static List<String> resolvePackages(OneCProperties properties, ApplicationContext context) {
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
