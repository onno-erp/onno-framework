package su.onno.spring;

import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.numbering.NumberGenerator;
import su.onno.repository.EnumerationPersistence;
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
 * Round-trips a catalog carrying an {@code @Enumeration} attribute through the typed Spring Data JDBC
 * repository ({@code save} / {@code findById}). This is the path issue #26 reported broken: the enum
 * was bound as its {@code name()} string into the {@code UUID} column because Spring Data JDBC
 * resolves an enum's column type to {@code String} before consulting the registered
 * {@code Enum -> UUID} converter. {@link OnnoJdbcConverter} fixes the column-type resolution.
 */
@SpringJUnitConfig(EnumAttributeRepositoryIT.Config.class)
class EnumAttributeRepositoryIT {

    @Autowired
    private TestServiceRepository repository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createSchema() {
        new SchemaGenerator(buildRegistry()).execute(Jdbi.create(dataSource));
    }

    @Test
    void catalogWithEnumAttribute_roundTripsThroughRepository() {
        TestService service = new TestService();
        service.setCode("S-1");
        service.setDescription("Rabies shot");
        service.setName("Rabies shot");
        service.setCategory(TestServiceCategory.VACCINATION);

        // save(...) used to fail: "Data conversion error converting 'VACCINATION' ... UUID"
        TestService saved = repository.save(service);
        UUID id = saved.getId();
        assertThat(id).isNotNull();

        TestService loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Rabies shot");
        assertThat(loaded.getCategory()).isEqualTo(TestServiceCategory.VACCINATION);

        // The column physically holds the stable name-based UUID for the enum value, not its name.
        UUID expectedEnumId = EnumerationPersistence.resolveId(TestServiceCategory.class,
                TestServiceCategory.VACCINATION);
        UUID storedCategory = Jdbi.create(dataSource).withHandle(h ->
                h.createQuery("SELECT category FROM catalog_test_services WHERE _id = :id")
                        .bind("id", id)
                        .mapTo(UUID.class)
                        .one());
        assertThat(storedCategory).isEqualTo(expectedEnumId);
    }

    @Test
    void enumStoredAsUuidTextInVarcharColumn_stillReadsBack() {
        // Reproduces issue #168: an attribute migrated from String to @Enumeration leaves the
        // pre-existing `varchar` column in place. onno still writes the enum's UUID, but the driver
        // hands it back as a String, so UuidToEnum never fires and the default Enum.valueOf(<uuid>)
        // used to throw. Simulate the legacy column by retyping it and storing the UUID as text.
        Jdbi jdbi = Jdbi.create(dataSource);
        jdbi.useHandle(h -> h.execute(
                "ALTER TABLE catalog_test_services ALTER COLUMN category SET DATA TYPE VARCHAR(64)"));

        UUID id = UUID.randomUUID();
        UUID categoryId = EnumerationPersistence.resolveId(TestServiceCategory.class,
                TestServiceCategory.SURGERY);
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO catalog_test_services (_id, _code, name, category) "
                                + "VALUES (:id, :code, :name, :category)")
                .bind("id", id)
                .bind("code", "S-3")
                .bind("name", "Spaying")
                .bind("category", categoryId.toString())
                .execute());

        TestService loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getCategory()).isEqualTo(TestServiceCategory.SURGERY);
    }

    @Test
    void nullEnumAttribute_savesAsNull() {
        TestService service = new TestService();
        service.setCode("S-2");
        service.setName("No category");
        // category left null

        TestService saved = repository.save(service);
        TestService loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getCategory()).isNull();
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
            ds.setURL("jdbc:h2:mem:enum_attr_it;DB_CLOSE_DELAY=-1");
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

        /** Register the framework's value-object converters, including {@code Enum <-> UUID}. */
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

        /** Mirror production wiring: the enum-aware converter is what makes this test pass. */
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
