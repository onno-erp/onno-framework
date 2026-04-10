package com.onec.spring;

import com.onec.metadata.*;
import com.onec.posting.PostingEngine;
import com.onec.posting.PostingService;
import com.onec.posting.RegisterPersistence;
import com.onec.posting.RegisterQueryService;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
    public SchemaInitializer schemaInitializer(DataSource dataSource, OneCProperties properties) {
        return new SchemaInitializer(dataSource, properties.getScanPackages());
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
    public PostingService postingService(DataSource dataSource, OneCProperties properties) {
        Jdbi jdbi = Jdbi.create(dataSource);
        MetadataRegistry registry = buildRegistry(properties.getScanPackages());
        Map<Class<?>, RegisterPersistence<?>> persistenceMap = buildRegisterPersistenceMap(jdbi, registry);
        PostingEngine engine = new PostingEngine(jdbi, registry, persistenceMap);
        return new PostingService(engine);
    }

    @Bean
    public RegisterQueryService registerQueryService(DataSource dataSource, OneCProperties properties) {
        Jdbi jdbi = Jdbi.create(dataSource);
        MetadataRegistry registry = buildRegistry(properties.getScanPackages());
        Map<Class<?>, RegisterPersistence<?>> persistenceMap = buildRegisterPersistenceMap(jdbi, registry);
        return new RegisterQueryService(persistenceMap);
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
}
