package com.onec.spring;

import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;
import com.onec.metadata.TabularSectionDescriptor;
import com.onec.numbering.NumberGenerator;
import com.onec.schema.SchemaGenerator;
import com.onec.security.SecretCipher;
import com.onec.spring.fixtures.TestStarterInvoice;
import com.onec.spring.fixtures.TestStarterInvoiceRepository;
import com.onec.spring.fixtures.TestStarterLine;
import com.onec.types.Ref;
import com.onec.types.RefResolver;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a document that carries a {@code @TabularSection} through the typed Spring Data JDBC
 * repository ({@code save} / {@code findById}) and through {@link RefResolver} — the path issue #24
 * reported broken because the schema generator and the JDBC mapping disagreed on the child table
 * name ({@code document_starter_invoices_items}) and back-reference column ({@code _parent_id}).
 */
@SpringJUnitConfig(TabularSectionRepositoryIT.Config.class)
class TabularSectionRepositoryIT {

    @Autowired
    private TestStarterInvoiceRepository repository;

    @Autowired
    private RefResolver refResolver;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createSchema() {
        SchemaGenerator schema = new SchemaGenerator(buildRegistry());
        schema.execute(Jdbi.create(dataSource));
    }

    @Test
    void documentWithTabularSection_roundTripsThroughRepositoryAndRefResolver() {
        TestStarterInvoice invoice = new TestStarterInvoice();
        invoice.setNumber("INV-1");
        invoice.setDate(LocalDateTime.of(2026, 6, 4, 10, 0));
        invoice.setCounterparty("Acme Corp");

        TestStarterLine widget = new TestStarterLine();
        widget.setProductName("Widget");
        widget.setQuantity(new BigDecimal("3"));
        TestStarterLine gadget = new TestStarterLine();
        gadget.setProductName("Gadget");
        gadget.setQuantity(new BigDecimal("5"));
        invoice.getItems().add(widget);
        invoice.getItems().add(gadget);

        // save(...) used to fail with "Failed to execute InsertRoot{...}"
        TestStarterInvoice saved = repository.save(invoice);
        UUID id = saved.getId();
        assertThat(id).isNotNull();

        // findById(...) used to fail with bad SQL grammar against the wrong child table/column
        TestStarterInvoice loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getCounterparty()).isEqualTo("Acme Corp");
        assertThat(loaded.getItems()).hasSize(2);
        assertThat(loaded.getItems())
                .extracting(TestStarterLine::getProductName)
                .containsExactly("Widget", "Gadget");
        assertThat(loaded.getItems())
                .extracting(TestStarterLine::getQuantity)
                .satisfies(qs -> {
                    assertThat(qs.get(0)).isEqualByComparingTo("3");
                    assertThat(qs.get(1)).isEqualByComparingTo("5");
                });
        assertThat(loaded.getItems().get(0).getLineNumber()).isEqualTo(1);
        assertThat(loaded.getItems().get(1).getLineNumber()).isEqualTo(2);

        // RefResolver.resolve(Ref<Doc>) delegates to the same repository.findById
        TestStarterInvoice viaRef = refResolver.resolveOrThrow(Ref.of(TestStarterInvoice.class, id));
        assertThat(viaRef.getItems())
                .extracting(TestStarterLine::getProductName)
                .containsExactly("Widget", "Gadget");
    }

    private static MetadataRegistry buildRegistry() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(TestStarterInvoice.class));
        return registry;
    }

    @Configuration
    @EnableJdbcRepositories(basePackageClasses = TestStarterInvoiceRepository.class)
    static class Config extends AbstractJdbcConfiguration {

        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:tabular_it;DB_CLOSE_DELAY=-1");
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
        NamingStrategy oneCNamingStrategy(MetadataRegistry registry) {
            Map<Class<?>, String> tabularTables = new HashMap<>();
            for (DocumentDescriptor doc : registry.allDocuments()) {
                for (TabularSectionDescriptor ts : doc.tabularSections()) {
                    tabularTables.put(ts.rowClass(), ts.tableName());
                }
            }
            return new OnecNamingStrategy(tabularTables);
        }

        @Override
        public JdbcMappingContext jdbcMappingContext(Optional<NamingStrategy> namingStrategy,
                                                     JdbcCustomConversions customConversions,
                                                     RelationalManagedTypes jdbcManagedTypes) {
            OnecMappingContext context = new OnecMappingContext(namingStrategy.orElseGet(OnecNamingStrategy::new));
            context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
            return context;
        }

        @Bean
        SecretCipher secretCipher() {
            return new SecretCipher("test-secret-key");
        }

        @Bean
        NumberGenerator numberGenerator() {
            // autoNumber=false on the fixture, so this is never invoked.
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
        OnecBeforeConvertCallback oneCBeforeConvertCallback(MetadataRegistry registry,
                                                            NumberGenerator numberGenerator,
                                                            SecretCipher secretCipher) {
            return new OnecBeforeConvertCallback(registry, numberGenerator, secretCipher);
        }

        @Bean
        OnecAfterConvertCallback oneCAfterConvertCallback(MetadataRegistry registry, SecretCipher secretCipher) {
            return new OnecAfterConvertCallback(registry, secretCipher);
        }

        @Bean
        OnecAfterSaveCallback oneCAfterSaveCallback(MetadataRegistry registry, SecretCipher secretCipher) {
            return new OnecAfterSaveCallback(null, registry, secretCipher);
        }

        @Bean
        RefResolver refResolver(ApplicationContext applicationContext) {
            return new SpringRefResolver(applicationContext);
        }
    }
}
