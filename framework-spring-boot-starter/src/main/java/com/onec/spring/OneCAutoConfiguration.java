package com.onec.spring;

import com.onec.metadata.*;
import com.onec.posting.PostingEngine;
import com.onec.posting.PostingService;
import com.onec.posting.RegisterPersistence;
import com.onec.repository.RegisterRepository;
import com.onec.repository.RegisterRepositoryImpl;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.relational.core.mapping.NamingStrategy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(OneCProperties.class)
@ConditionalOnBean(DataSource.class)
public class OneCAutoConfiguration extends AbstractJdbcConfiguration {

    @Bean
    public NamingStrategy oneCNamingStrategy() {
        return new OneCNamingStrategy();
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
    public OneCBeforeConvertCallback oneCBeforeConvertCallback() {
        return new OneCBeforeConvertCallback();
    }

    @Bean
    public OneCAfterSaveCallback oneCAfterSaveCallback() {
        return new OneCAfterSaveCallback();
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
    public Map<Class<?>, RegisterPersistence<?>> registerPersistenceMap(Jdbi jdbi, MetadataRegistry registry) {
        return buildRegisterPersistenceMap(jdbi, registry);
    }

    @Bean
    public PostingService postingService(Jdbi jdbi, MetadataRegistry registry,
                                         Map<Class<?>, RegisterPersistence<?>> persistenceMap) {
        PostingEngine engine = new PostingEngine(jdbi, registry, persistenceMap);
        return new PostingService(engine);
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<Class<?>, RegisterRepository<?>> registerRepositories(
            Map<Class<?>, RegisterPersistence<?>> persistenceMap) {
        Map<Class<?>, RegisterRepository<?>> repos = new HashMap<>();
        for (var entry : persistenceMap.entrySet()) {
            repos.put(entry.getKey(), new RegisterRepositoryImpl(entry.getValue()));
        }
        return repos;
    }

    @Bean
    public RegisterRepositoryProvider registerRepositoryProvider(
            Map<Class<?>, RegisterRepository<?>> registerRepositories) {
        return new RegisterRepositoryProvider(registerRepositories);
    }

    private MetadataRegistry buildRegistry(List<String> scanPackages) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        for (Class<?> clazz : new DocumentScanner().scan(scanPackages)) {
            registry.registerDocument(scanner.scanDocument(clazz));
        }
        for (Class<?> clazz : new AccumulationScanner().scan(scanPackages)) {
            registry.registerAccumulation(scanner.scanRegister(clazz));
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

    private List<String> resolvePackages(OneCProperties properties, ApplicationContext context) {
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
