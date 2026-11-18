package su.onno.posting;

import su.onno.fixtures.TestCostIssue;
import su.onno.fixtures.TestCostReceipt;
import su.onno.fixtures.TestCostRegister;
import su.onno.fixtures.TestOverdraftRegister;
import su.onno.fixtures.TestReceipt;
import su.onno.fixtures.TestStockIssue;
import su.onno.fixtures.TestStockRegister;
import su.onno.fixtures.TestWithdrawal;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.repository.RegisterRepositoryImpl;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The same H2 + generated-schema + {@link PostingEngine} wiring {@code PostingTest} builds, packaged
 * so property tests can share one database across tries (each try isolates itself by using fresh
 * dimension values, and for chronological registers a fresh date window).
 */
final class PostingHarness {

    final Jdbi jdbi;
    final MetadataRegistry registry = new MetadataRegistry();
    final PostingEngine engine;
    final RegisterPersistence<TestStockRegister> stock;
    final RegisterPersistence<TestCostRegister> cost;
    /** Stands in for the repository-backed {@link PostedDocumentLoader}: every inserted document. */
    final Map<UUID, DocumentObject> documents = new ConcurrentHashMap<>();

    PostingHarness() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:posting-properties-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(TestReceipt.class));
        registry.registerDocument(scanner.scanDocument(TestStockIssue.class));
        registry.registerDocument(scanner.scanDocument(TestWithdrawal.class));
        registry.registerDocument(scanner.scanDocument(TestCostReceipt.class));
        registry.registerDocument(scanner.scanDocument(TestCostIssue.class));
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));
        registry.registerAccumulation(scanner.scanRegister(TestOverdraftRegister.class));
        registry.registerAccumulation(scanner.scanRegister(TestCostRegister.class));
        new SchemaGenerator(registry).execute(jdbi);

        stock = new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(TestStockRegister.class));
        cost = new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(TestCostRegister.class));
        Map<Class<?>, RegisterRepositoryImpl<?>> repositories = new HashMap<>();
        repositories.put(TestStockRegister.class, new RegisterRepositoryImpl<>(stock, TestStockRegister.class));
        repositories.put(TestCostRegister.class, new RegisterRepositoryImpl<>(cost, TestCostRegister.class));
        repositories.put(TestOverdraftRegister.class, new RegisterRepositoryImpl<>(
                new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(TestOverdraftRegister.class)),
                TestOverdraftRegister.class));
        engine = new PostingEngine(jdbi, registry, repositories, null, null,
                ids -> ids.stream().map(documents::get).filter(Objects::nonNull).toList());
    }

    /** Inserts the document header row (unposted) so posting can claim it, and registers it with the loader. */
    void insert(DocumentObject document, Map<String, Object> columns) {
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(document.getClass());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("_id", document.getId());
        values.put("_number", document.getNumber());
        values.put("_date", document.getDate());
        values.put("_posted", false);
        values.put("_deletion_mark", false);
        values.putAll(columns);
        String names = String.join(", ", values.keySet());
        String binds = values.keySet().stream().map(k -> ":" + k).reduce((a, b) -> a + ", " + b).orElseThrow();
        jdbi.useHandle(h -> {
            var update = h.createUpdate("INSERT INTO " + descriptor.tableName() + " (" + names + ") VALUES (" + binds + ")");
            values.forEach(update::bind);
            update.execute();
        });
        documents.put(document.getId(), document);
    }

    boolean isPosted(DocumentObject document) {
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(document.getClass());
        return jdbi.withHandle(h -> h.createQuery("SELECT _posted FROM " + descriptor.tableName() + " WHERE _id = :id")
                .bind("id", document.getId()).mapTo(Boolean.class).one());
    }

    /** Stored totals of a BALANCE register for the given dimension columns, keyed by the dimension tuple. */
    Map<List<Object>, List<BigDecimal>> totals(Class<?> register, List<String> dimensions, List<String> resources,
                                               String where, Map<String, Object> binds) {
        AccumulationRegisterDescriptor descriptor = registry.getRegisterDescriptor(register);
        return rows("SELECT " + String.join(", ", dimensions) + ", " + String.join(", ", resources)
                + " FROM " + descriptor.totalsTableName() + " WHERE " + where, dimensions, resources, binds);
    }

    /** The same shape recomputed from the ACTIVE movements: receipts add, expenses subtract. */
    Map<List<Object>, List<BigDecimal>> movementSums(Class<?> register, List<String> dimensions, List<String> resources,
                                                     String where, Map<String, Object> binds) {
        AccumulationRegisterDescriptor descriptor = registry.getRegisterDescriptor(register);
        String sums = resources.stream()
                .map(r -> "SUM(CASE WHEN _movement_type = 'RECEIPT' THEN " + r + " ELSE -" + r + " END) AS " + r)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        return rows("SELECT " + String.join(", ", dimensions) + ", " + sums + " FROM " + descriptor.tableName()
                + " WHERE _active = TRUE AND (" + where + ") GROUP BY " + String.join(", ", dimensions),
                dimensions, resources, binds);
    }

    private Map<List<Object>, List<BigDecimal>> rows(String sql, List<String> dimensions, List<String> resources,
                                                     Map<String, Object> binds) {
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql);
            binds.forEach(query::bind);
            Map<List<Object>, List<BigDecimal>> result = new HashMap<>();
            for (Map<String, Object> row : query.mapToMap().list()) {
                List<Object> key = dimensions.stream().map(d -> row.get(d.toLowerCase())).toList();
                List<BigDecimal> value = resources.stream()
                        .map(r -> (BigDecimal) row.get(r.toLowerCase()))
                        .map(v -> v == null ? BigDecimal.ZERO : v.stripTrailingZeros())
                        .toList();
                result.put(key, value);
            }
            return result;
        });
    }
}
