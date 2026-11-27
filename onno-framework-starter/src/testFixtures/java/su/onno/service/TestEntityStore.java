package su.onno.service;

import su.onno.history.RecordHistory;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.numbering.NumberGenerator;
import su.onno.period.PeriodCloseService;
import su.onno.security.SecretCipher;
import su.onno.spring.EnumUuidConverters;
import su.onno.spring.OnnoAfterConvertCallback;
import su.onno.spring.OnnoAfterSaveCallback;
import su.onno.spring.OnnoBeforeConvertCallback;
import su.onno.spring.OnnoJdbcConverter;
import su.onno.spring.OnnoMappingContext;
import su.onno.spring.OnnoNamingStrategy;
import su.onno.spring.RecordHistoryCallback;
import su.onno.spring.TransactionalEntityChangePublisher;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.spring5.SpringConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.jdbc.core.convert.DefaultJdbcTypeFactory;
import org.springframework.data.jdbc.core.convert.JdbcArrayColumns;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.convert.RelationResolver;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Builds the framework's Spring Data JDBC persistence — Onno mapping, enum converters, and the
 * before-convert / after-save / after-convert / history callbacks — on a test's own
 * {@link DataSource} and {@link MetadataRegistry}, and hands back an {@link EntityStore} plus a
 * {@link Jdbi} that joins the store's transactions. Service tests construct the command services on
 * these without booting the application.
 *
 * <pre>{@code
 * var stack = TestEntityStore.builder(dataSource, registry).events(events::add).build();
 * var commands = new CatalogCommandService(registry, stack.jdbi(), props, query, access, stack.store());
 * }</pre>
 */
public final class TestEntityStore {

    private TestEntityStore() {
    }

    /** The store, a transaction-joining JDBI, and the context that owns them (close it to release). */
    public record Stack(EntityStore store, Jdbi jdbi, AnnotationConfigApplicationContext context)
            implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }

    public static Builder builder(DataSource dataSource, MetadataRegistry registry) {
        return new Builder(dataSource, registry);
    }

    public static final class Builder {
        private final DataSource dataSource;
        private final MetadataRegistry registry;
        private NumberGenerator numbers;
        private SecretCipher cipher = new SecretCipher(null);
        private Consumer<Object> events = event -> { };
        private RecordHistory history;
        private PeriodCloseService periodClose;

        private Builder(DataSource dataSource, MetadataRegistry registry) {
            this.dataSource = dataSource;
            this.registry = registry;
        }

        /** Code/number sequence; defaults to an in-memory counter per sequence. */
        public Builder numbers(NumberGenerator numbers) {
            this.numbers = numbers;
            return this;
        }

        public Builder cipher(SecretCipher cipher) {
            this.cipher = cipher;
            return this;
        }

        /** Receives every application event, e.g. the commit-deferred {@code EntityChangedEvent}. */
        public Builder events(Consumer<Object> events) {
            this.events = events;
            return this;
        }

        public Builder history(RecordHistory history) {
            this.history = history;
            return this;
        }

        public Builder periodClose(PeriodCloseService periodClose) {
            this.periodClose = periodClose;
            return this;
        }

        public Stack build() {
            Options options = new Options(registry, numbers == null ? new CountingNumbers() : numbers,
                    cipher, history, periodClose);
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getBeanFactory().registerSingleton("testEntityStoreOptions", options);
            context.register(StoreConfiguration.class);
            Consumer<Object> sink = events;
            context.addApplicationListener(event -> {
                if (event instanceof PayloadApplicationEvent<?> payload) {
                    sink.accept(payload.getPayload());
                }
            });
            context.refresh();
            EntityStore store = new EntityStore(context.getBean(JdbcAggregateOperations.class),
                    context.getBean(PlatformTransactionManager.class));
            return new Stack(store, context.getBean(Jdbi.class), context);
        }
    }

    record Options(MetadataRegistry registry, NumberGenerator numbers, SecretCipher cipher,
                   RecordHistory history, PeriodCloseService periodClose) {
    }

    /** Mirrors the persistence half of {@code OnnoAutoConfiguration} for an explicit registry. */
    @Configuration(proxyBeanMethods = false)
    static class StoreConfiguration extends AbstractJdbcConfiguration {

        @Autowired
        private Options options;

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        Jdbi jdbi(DataSource dataSource) {
            return Jdbi.create(new SpringConnectionFactory(dataSource));
        }

        @Override
        protected List<?> userConverters() {
            return EnumUuidConverters.build(options.registry().allEnumerations());
        }

        @Override
        public JdbcMappingContext jdbcMappingContext(Optional<NamingStrategy> namingStrategy,
                                                     JdbcCustomConversions customConversions,
                                                     RelationalManagedTypes jdbcManagedTypes) {
            Map<Class<?>, String> sectionTables = new LinkedHashMap<>();
            List<TabularSectionDescriptor> sections = new java.util.ArrayList<>();
            options.registry().allCatalogs().forEach(c -> sections.addAll(c.tabularSections()));
            options.registry().allDocuments().forEach(d -> sections.addAll(d.tabularSections()));
            sections.forEach(ts -> sectionTables.putIfAbsent(ts.rowClass(), ts.tableName()));
            OnnoMappingContext context = new OnnoMappingContext(new OnnoNamingStrategy(sectionTables));
            context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
            return context;
        }

        @Override
        public JdbcConverter jdbcConverter(JdbcMappingContext mappingContext,
                                           NamedParameterJdbcOperations operations,
                                           @org.springframework.context.annotation.Lazy RelationResolver relationResolver,
                                           JdbcCustomConversions conversions, Dialect dialect) {
            JdbcArrayColumns arrayColumns = dialect instanceof JdbcDialect jdbcDialect
                    ? jdbcDialect.getArraySupport() : JdbcArrayColumns.DefaultSupport.INSTANCE;
            return new OnnoJdbcConverter(mappingContext, relationResolver, conversions,
                    new DefaultJdbcTypeFactory(operations.getJdbcOperations(), arrayColumns));
        }

        @Bean
        OnnoBeforeConvertCallback onnoBeforeConvertCallback(Jdbi jdbi) {
            OnnoBeforeConvertCallback callback =
                    new OnnoBeforeConvertCallback(options.registry(), options.numbers(), options.cipher());
            callback.setPeriodCloseService(options.periodClose());
            callback.setCatalogHierarchyValidator(new CatalogHierarchyValidator(jdbi));
            return callback;
        }

        @Bean
        OnnoAfterSaveCallback onnoAfterSaveCallback(org.springframework.context.ApplicationEventPublisher events) {
            return new OnnoAfterSaveCallback(null, options.registry(), options.cipher(),
                    new TransactionalEntityChangePublisher(events));
        }

        @Bean
        OnnoAfterConvertCallback onnoAfterConvertCallback() {
            return new OnnoAfterConvertCallback(options.registry(), options.cipher());
        }

        @Bean
        RecordHistoryCallback recordHistoryCallback(Jdbi jdbi) {
            return new RecordHistoryCallback(
                    options.history() == null ? RecordHistory.disabled() : options.history(), jdbi);
        }
    }

    /** An in-memory sequence per name: 1, 2, 3… zero-padded to the requested length. */
    static final class CountingNumbers implements NumberGenerator {
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

        @Override
        public String nextNumber(String entityName, int length) {
            return next(entityName, length);
        }

        @Override
        public String nextCode(String entityName, int length) {
            return next(entityName, length);
        }

        private String next(String name, int length) {
            long value = counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
            return String.format("%0" + Math.max(1, length) + "d", value);
        }
    }
}
