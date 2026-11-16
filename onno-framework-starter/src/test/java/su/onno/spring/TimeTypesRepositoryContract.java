package su.onno.spring;

import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.schema.SchemaGenerator;
import su.onno.spring.fixtures.timetypes.TimedShift;
import su.onno.spring.fixtures.timetypes.TimedShiftBreak;
import su.onno.spring.fixtures.timetypes.TimedShiftRepository;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
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
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code LocalTime} / {@code Instant} attributes through the typed Spring Data JDBC repository
 * ({@code save} / {@code findById}), header and tabular rows (#548). The H2 and PostgreSQL
 * subclasses supply the data source.
 */
abstract class TimeTypesRepositoryContract {

    @Autowired
    private TimedShiftRepository repository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createSchema() {
        new SchemaGenerator(buildRegistry()).execute(Jdbi.create(dataSource));
    }

    @Test
    void saveAndLoad_roundTripsTimeAndInstant() {
        TimedShift shift = new TimedShift();
        shift.setCode("T-" + System.nanoTime() % 1_000_000);
        shift.setStartsAt(LocalTime.of(9, 30));
        shift.setOpenedAt(Instant.parse("2026-01-16T01:00:00Z"));
        TimedShiftBreak pause = new TimedShiftBreak();
        pause.setAt(LocalTime.of(13, 0, 15));
        pause.setTakenAt(Instant.parse("2026-07-16T11:00:15Z"));
        shift.getBreaks().add(pause);

        TimedShift loaded = repository.findById(repository.save(shift).getId()).orElseThrow();
        assertThat(loaded.getStartsAt()).isEqualTo(LocalTime.of(9, 30));
        assertThat(loaded.getOpenedAt()).isEqualTo(Instant.parse("2026-01-16T01:00:00Z"));
        assertThat(loaded.getBreaks()).singleElement().satisfies(b -> {
            assertThat(b.getAt()).isEqualTo(LocalTime.of(13, 0, 15));
            assertThat(b.getTakenAt()).isEqualTo(Instant.parse("2026-07-16T11:00:15Z"));
        });

        // The column holds the absolute moment, independent of the JVM's default zone.
        Instant stored = Jdbi.create(dataSource).withHandle(h -> h.createQuery(
                        "SELECT opened_at FROM catalog_timed_shifts WHERE _id = :id")
                .bind("id", loaded.getId()).mapTo(Instant.class).one());
        assertThat(stored).isEqualTo(Instant.parse("2026-01-16T01:00:00Z"));

        // Update path: load, mutate, save.
        loaded.setStartsAt(LocalTime.of(10, 0));
        loaded.setOpenedAt(null);
        repository.save(loaded);
        TimedShift updated = repository.findById(loaded.getId()).orElseThrow();
        assertThat(updated.getStartsAt()).isEqualTo(LocalTime.of(10, 0));
        assertThat(updated.getOpenedAt()).isNull();
    }

    static MetadataRegistry buildRegistry() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(TimedShift.class));
        return registry;
    }

    /** Production-equivalent Spring Data JDBC wiring; subclasses add the {@link DataSource}. */
    abstract static class BaseConfig extends AbstractJdbcConfiguration {

        @Bean
        NamedParameterJdbcOperations namedParameterJdbcOperations(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MetadataRegistry metadataRegistry() {
            return buildRegistry();
        }

        @Bean
        NamingStrategy onnoNamingStrategy(MetadataRegistry registry) {
            java.util.Map<Class<?>, String> tabularTables = new java.util.HashMap<>();
            for (var catalog : registry.allCatalogs()) {
                for (var section : catalog.tabularSections()) {
                    tabularTables.put(section.rowClass(), section.tableName());
                }
            }
            return new OnnoNamingStrategy(tabularTables);
        }

        @Bean
        su.onno.security.SecretCipher secretCipher() {
            return new su.onno.security.SecretCipher("test-secret-key");
        }

        @Bean
        OnnoBeforeConvertCallback onnoBeforeConvertCallback(MetadataRegistry registry,
                                                            su.onno.security.SecretCipher secretCipher) {
            su.onno.numbering.NumberGenerator numbers = new su.onno.numbering.NumberGenerator() {
                @Override public String nextNumber(String entityName, int length) { return "0"; }
                @Override public String nextCode(String entityName, int length) { return "0"; }
            };
            return new OnnoBeforeConvertCallback(registry, numbers, secretCipher);
        }

        @Bean
        OnnoAfterConvertCallback onnoAfterConvertCallback(MetadataRegistry registry,
                                                          su.onno.security.SecretCipher secretCipher) {
            return new OnnoAfterConvertCallback(registry, secretCipher);
        }

        @Bean
        OnnoAfterSaveCallback onnoAfterSaveCallback(MetadataRegistry registry,
                                                    su.onno.security.SecretCipher secretCipher) {
            return new OnnoAfterSaveCallback(null, registry, secretCipher, null);
        }

        @Override
        protected List<?> userConverters() {
            return EnumUuidConverters.build(List.of());
        }

        @Override
        public JdbcMappingContext jdbcMappingContext(Optional<NamingStrategy> namingStrategy,
                                                     JdbcCustomConversions customConversions,
                                                     RelationalManagedTypes jdbcManagedTypes) {
            OnnoMappingContext context = new OnnoMappingContext(namingStrategy.orElseGet(OnnoNamingStrategy::new));
            context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
            return context;
        }

        @Override
        public JdbcConverter jdbcConverter(JdbcMappingContext mappingContext,
                                           NamedParameterJdbcOperations operations,
                                           @Lazy RelationResolver relationResolver,
                                           JdbcCustomConversions conversions,
                                           Dialect dialect) {
            var arrayColumns = dialect instanceof JdbcDialect jdbcDialect
                    ? jdbcDialect.getArraySupport()
                    : JdbcArrayColumns.DefaultSupport.INSTANCE;
            return new OnnoJdbcConverter(mappingContext, relationResolver, conversions,
                    new DefaultJdbcTypeFactory(operations.getJdbcOperations(), arrayColumns));
        }
    }
}
