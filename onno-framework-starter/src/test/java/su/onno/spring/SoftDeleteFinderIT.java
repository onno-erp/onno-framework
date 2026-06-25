package su.onno.spring;

import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.spring.fixtures.TestService;
import su.onno.spring.fixtures.TestServiceCategory;
import su.onno.spring.fixtures.TestServiceRepository;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.convert.RelationResolver;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the soft-delete-aware finders on {@link su.onno.repository.CatalogRepository} resolve as
 * Spring Data JDBC derived queries (against the real {@link OnnoNamingStrategy}, so {@code deletionMark}
 * maps to the {@code _deletion_mark} column) and exclude rows marked for deletion — while the
 * inherited {@code findAll()}/{@code findById()}/{@code findByCode()} still return those tombstones.
 *
 * <p>This is the contract that keeps a "deleted" catalog row from silently taking effect in business
 * logic (e.g. a deleted employee still being admitted by a Telegram-login resolver).</p>
 */
@SpringJUnitConfig(SoftDeleteFinderIT.Config.class)
class SoftDeleteFinderIT {

    @Autowired
    private TestServiceRepository repository;

    @Autowired
    private DataSource dataSource;

    private UUID liveId;
    private UUID deletedId;

    @BeforeEach
    void seed() {
        Jdbi jdbi = Jdbi.create(dataSource);
        new SchemaGenerator(buildRegistry()).execute(jdbi);
        // The in-memory H2 (singleton datasource, DB_CLOSE_DELAY=-1) persists across @BeforeEach
        // runs and SchemaGenerator is idempotent, so clear rows to start each test from exactly 3.
        jdbi.useHandle(h -> h.execute("DELETE FROM catalog_test_services"));

        liveId = save("S-1", "Rabies shot", false);
        save("S-2", "Microchipping", false);
        deletedId = save("S-3", "Discontinued service", true);
    }

    @Test
    void findAllActive_excludesDeletionMarkedRows() {
        assertThat(repository.findAll()).hasSize(3);          // raw finder still sees the tombstone
        assertThat(repository.findAllActive())
                .extracting(TestService::getCode)
                .containsExactlyInAnyOrder("S-1", "S-2");      // the deleted row is gone
    }

    @Test
    void findActiveByCode_hidesDeletedButRawFinderDoesNot() {
        assertThat(repository.findActiveByCode("S-1")).isPresent();
        assertThat(repository.findActiveByCode("S-3")).isEmpty();
        assertThat(repository.findByCode("S-3")).isPresent();  // escape hatch still resolves it
    }

    @Test
    void findActiveById_hidesDeletedButRawFinderDoesNot() {
        assertThat(repository.findActiveById(liveId)).isPresent();
        assertThat(repository.findActiveById(deletedId)).isEmpty();
        assertThat(repository.findById(deletedId)).isPresent(); // RefResolver path still resolves it
    }

    private UUID save(String code, String name, boolean deletionMark) {
        TestService s = new TestService();
        s.setCode(code);
        s.setDescription(name);
        s.setName(name);
        s.setCategory(TestServiceCategory.VACCINATION);
        UUID id = repository.save(s).getId();
        if (deletionMark) {
            TestService loaded = repository.findById(id).orElseThrow(); // isNew=false -> next save UPDATEs
            loaded.setDeletionMark(true);
            repository.save(loaded);
        }
        return id;
    }

    private static MetadataRegistry buildRegistry() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestService.class));
        registry.registerEnumeration(scanner.scanEnumeration(TestServiceCategory.class));
        return registry;
    }

    @Configuration
    @EnableJdbcRepositories(basePackageClasses = TestServiceRepository.class)
    static class Config extends AbstractJdbcConfiguration {

        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:soft_delete_it;DB_CLOSE_DELAY=-1");
            return ds;
        }

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
        NamingStrategy onnoNamingStrategy() {
            return new OnnoNamingStrategy();
        }

        @Override
        protected List<?> userConverters() {
            MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
            EnumerationDescriptor category = scanner.scanEnumeration(TestServiceCategory.class);
            return EnumUuidConverters.build(List.of(category));
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
                                           @org.springframework.context.annotation.Lazy RelationResolver relationResolver,
                                           JdbcCustomConversions conversions,
                                           Dialect dialect) {
            var arrayColumns = dialect instanceof org.springframework.data.jdbc.core.dialect.JdbcDialect jdbcDialect
                    ? jdbcDialect.getArraySupport()
                    : org.springframework.data.jdbc.core.convert.JdbcArrayColumns.DefaultSupport.INSTANCE;
            var jdbcTypeFactory = new org.springframework.data.jdbc.core.convert.DefaultJdbcTypeFactory(
                    operations.getJdbcOperations(), arrayColumns);
            return new OnnoJdbcConverter(mappingContext, relationResolver, conversions, jdbcTypeFactory);
        }

        @Bean
        SecretCipher secretCipher() {
            return new SecretCipher("test-secret-key");
        }

        @Bean
        NumberGenerator numberGenerator() {
            return new NumberGenerator() {
                @Override
                public String nextNumber(String entityName, int length) {
                    return "0";
                }

                @Override
                public String nextCode(String entityName, int length) {
                    return "0";
                }
            };
        }

        @Bean
        OnnoBeforeConvertCallback onnoBeforeConvertCallback(MetadataRegistry registry,
                                                            NumberGenerator numberGenerator,
                                                            SecretCipher secretCipher) {
            return new OnnoBeforeConvertCallback(registry, numberGenerator, secretCipher);
        }

        @Bean
        OnnoAfterConvertCallback onnoAfterConvertCallback(MetadataRegistry registry, SecretCipher secretCipher) {
            return new OnnoAfterConvertCallback(registry, secretCipher);
        }

        @Bean
        OnnoAfterSaveCallback onnoAfterSaveCallback(MetadataRegistry registry, SecretCipher secretCipher) {
            return new OnnoAfterSaveCallback(null, registry, secretCipher, null);
        }
    }
}
