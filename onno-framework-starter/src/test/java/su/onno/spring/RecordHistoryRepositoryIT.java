package su.onno.spring;

import su.onno.history.HistoryActor;
import su.onno.history.HistoryActors;
import su.onno.history.HistoryChange;
import su.onno.history.HistoryEntry;
import su.onno.history.RecordHistory;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.spring.historyfixtures.HistoryVisit;
import su.onno.spring.historyfixtures.HistoryVisitLine;
import su.onno.spring.historyfixtures.HistoryVisitRepository;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Record change history (#422) on the typed repository path: {@code repository.save(...)} and
 * {@code delete(...)} of an opted-in document record the same field-level entries as the generic
 * command path, inside the repository's transaction (post/unpost entries are covered by the core {@code PostingHistoryTest}).
 */
@SpringJUnitConfig(RecordHistoryRepositoryIT.Config.class)
class RecordHistoryRepositoryIT {

    @Autowired
    private HistoryVisitRepository visits;

    @Autowired
    private RecordHistory history;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createSchema() {
        new SchemaGenerator(buildRegistry()).execute(Jdbi.create(dataSource));
    }

    @Test
    void repositorySavesAndDeletesRecordFieldLevelHistory() {
        HistoryVisit visit = newVisit("V-1", "limping");
        visit.setAccessCode("code-1");
        visit.getProcedures().add(line("X-ray"));
        UUID id = HistoryActors.callAs(HistoryActor.named("vet@clinic.test"), () -> visits.save(visit)).getId();

        HistoryVisit loaded = visits.findById(id).orElseThrow();
        loaded.setComplaint("limping badly");
        loaded.getProcedures().add(line("Bandage"));
        visits.save(loaded);

        // An unchanged save (the secret is re-encrypted to new ciphertext) records nothing.
        visits.save(visits.findById(id).orElseThrow());

        visits.delete(visits.findById(id).orElseThrow());

        List<HistoryEntry> entries = history.list(id, null, 10);
        assertThat(entries).extracting(HistoryEntry::event)
                .containsExactly(RecordHistory.DELETED, RecordHistory.UPDATED, RecordHistory.CREATED);
        assertThat(entries.get(2).actor()).isEqualTo("vet@clinic.test");
        assertThat(entries.get(1).actor()).as("no actor bound, no resolver").isEqualTo("system");

        assertThat(entries.get(1).changes()).containsExactlyInAnyOrder(
                new HistoryChange(null, null, null, "complaint", "limping", "limping badly", false),
                new HistoryChange("procedures", 2, HistoryChange.ROW_ADDED, "title", null, "Bandage", false));
        assertThat(entries.get(2).changes()).contains(
                new HistoryChange(null, null, null, "number", null, "V-1", false),
                new HistoryChange(null, null, null, "accessCode", null, null, true),
                new HistoryChange("procedures", 1, HistoryChange.ROW_ADDED, "title", null, "X-ray", false));
    }

    @Test
    void historyRollsBackWithTheRepositoryTransaction() {
        UUID id = visits.save(newVisit("V-2", "cough")).getId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            HistoryVisit loaded = visits.findById(id).orElseThrow();
            loaded.setComplaint("sneeze");
            visits.save(loaded);
            status.setRollbackOnly();
        });

        assertThat(history.list(id, null, 10)).extracting(HistoryEntry::event)
                .containsExactly(RecordHistory.CREATED);
        assertThat(visits.findById(id).orElseThrow().getComplaint()).isEqualTo("cough");
    }

    private static HistoryVisit newVisit(String number, String complaint) {
        HistoryVisit visit = new HistoryVisit();
        visit.setNumber(number);
        visit.setDate(LocalDateTime.of(2026, 11, 13, 10, 0));
        visit.setComplaint(complaint);
        return visit;
    }

    private static HistoryVisitLine line(String title) {
        HistoryVisitLine line = new HistoryVisitLine();
        line.setTitle(title);
        return line;
    }

    private static MetadataRegistry buildRegistry() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(new MetadataScanner(new DefaultNamingStrategy()).scanDocument(HistoryVisit.class));
        return registry;
    }

    @Configuration
    @EnableJdbcRepositories(
            basePackageClasses = HistoryVisitRepository.class,
            repositoryBaseClass = OnnoSimpleJdbcRepository.class)
    static class Config extends AbstractJdbcConfiguration {

        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:record_history_it;DB_CLOSE_DELAY=-1");
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
        NamingStrategy onnoNamingStrategy(MetadataRegistry registry) {
            Map<Class<?>, String> tabularTables = new HashMap<>();
            for (DocumentDescriptor doc : registry.allDocuments()) {
                for (TabularSectionDescriptor ts : doc.tabularSections()) {
                    tabularTables.put(ts.rowClass(), ts.tableName());
                }
            }
            return new OnnoNamingStrategy(tabularTables);
        }

        @Override
        @org.springframework.lang.NonNull
        public JdbcMappingContext jdbcMappingContext(Optional<NamingStrategy> namingStrategy,
                                                     @org.springframework.lang.NonNull JdbcCustomConversions customConversions,
                                                     @org.springframework.lang.NonNull RelationalManagedTypes jdbcManagedTypes) {
            OnnoMappingContext context = new OnnoMappingContext(namingStrategy.orElseGet(OnnoNamingStrategy::new));
            context.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
            return context;
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

        /** The Spring-transaction-aware JDBI the starter wires, so history joins the repository's transaction. */
        @Bean
        Jdbi jdbi(DataSource dataSource) {
            return new OnnoAutoConfiguration().jdbi(dataSource);
        }

        @Bean
        RecordHistory recordHistory(Jdbi jdbi, MetadataRegistry registry, SecretCipher secretCipher) {
            return new RecordHistory(jdbi, registry, secretCipher, null);
        }

        @Bean
        RecordHistoryCallback recordHistoryCallback(RecordHistory recordHistory, Jdbi jdbi) {
            return new RecordHistoryCallback(recordHistory, jdbi);
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
            return new OnnoAfterSaveCallback(null, registry, secretCipher);
        }
    }
}
