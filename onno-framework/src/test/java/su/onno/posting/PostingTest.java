package su.onno.posting;

import su.onno.fixtures.TestDeclarativeReceipt;
import su.onno.fixtures.TestCostIssue;
import su.onno.fixtures.TestCostReceipt;
import su.onno.fixtures.TestCostRegister;
import su.onno.fixtures.TestOverdraftRegister;
import su.onno.fixtures.TestReceipt;
import su.onno.fixtures.TestReceiptLine;
import su.onno.fixtures.TestSecretDocument;
import su.onno.fixtures.TestSecretLine;
import su.onno.fixtures.TestStockRegister;
import su.onno.fixtures.TestWithdrawal;
import su.onno.metadata.*;
import su.onno.messaging.OutboxWriter;
import su.onno.model.DocumentObject;
import su.onno.repository.RegisterRepositoryImpl;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class PostingTest {

    private Jdbi jdbi;
    private PostingEngine engine;
    private RegisterPersistence<TestStockRegister> stockPersistence;
    private RegisterPersistence<TestOverdraftRegister> overdraftPersistence;
    private RegisterPersistence<TestCostRegister> costPersistence;
    private MetadataRegistry registry;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        registry.registerDocument(scanner.scanDocument(TestReceipt.class));
        registry.registerDocument(scanner.scanDocument(TestDeclarativeReceipt.class));
        registry.registerDocument(scanner.scanDocument(TestWithdrawal.class));
        registry.registerDocument(scanner.scanDocument(TestCostReceipt.class));
        registry.registerDocument(scanner.scanDocument(TestCostIssue.class));
        registry.registerDocument(scanner.scanDocument(TestSecretDocument.class));
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));
        registry.registerAccumulation(scanner.scanRegister(TestOverdraftRegister.class));
        registry.registerAccumulation(scanner.scanRegister(TestCostRegister.class));

        SchemaGenerator schema = new SchemaGenerator(registry);
        schema.execute(jdbi);

        AccumulationRegisterDescriptor stockDesc = registry.getRegisterDescriptor(TestStockRegister.class);
        stockPersistence = new RegisterPersistence<>(jdbi, stockDesc);
        AccumulationRegisterDescriptor overdraftDesc =
                registry.getRegisterDescriptor(TestOverdraftRegister.class);
        overdraftPersistence = new RegisterPersistence<>(jdbi, overdraftDesc);
        AccumulationRegisterDescriptor costDesc =
                registry.getRegisterDescriptor(TestCostRegister.class);
        costPersistence = new RegisterPersistence<>(jdbi, costDesc);

        RegisterRepositoryImpl<TestStockRegister> stockRepo =
                new RegisterRepositoryImpl<>(stockPersistence, TestStockRegister.class);
        RegisterRepositoryImpl<TestOverdraftRegister> overdraftRepo =
                new RegisterRepositoryImpl<>(overdraftPersistence, TestOverdraftRegister.class);

        Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap = new HashMap<>();
        repositoryMap.put(TestStockRegister.class, stockRepo);
        repositoryMap.put(TestOverdraftRegister.class, overdraftRepo);
        repositoryMap.put(TestCostRegister.class,
                new RegisterRepositoryImpl<>(costPersistence, TestCostRegister.class));

        engine = new PostingEngine(jdbi, registry, repositoryMap);
    }

    private TestDeclarativeReceipt createDeclarativeReceipt(UUID warehouse, UUID product, BigDecimal qty) {
        TestDeclarativeReceipt receipt = new TestDeclarativeReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("DR-001");
        receipt.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        receipt.setWarehouse(warehouse);

        DocumentDescriptor docDesc = registry.getDocumentDescriptor(TestDeclarativeReceipt.class);
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO " + docDesc.tableName() + " (_id, _number, _date, _posted, _deletion_mark) " +
                        "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", receipt.getId())
                .bind("number", receipt.getNumber())
                .bind("date", receipt.getDate())
                .execute());

        TestReceiptLine line = new TestReceiptLine();
        line.setProduct(product);
        line.setQuantity(qty);
        receipt.getItems().add(line);
        return receipt;
    }

    private TestReceipt createReceipt(UUID warehouse, UUID product, BigDecimal qty) {
        TestReceipt receipt = new TestReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("REC-001");
        receipt.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        receipt.setWarehouse(warehouse);

        // Insert doc row so posting can UPDATE _posted
        DocumentDescriptor docDesc = registry.getDocumentDescriptor(TestReceipt.class);
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO " + docDesc.tableName() + " (_id, _number, _date, _posted, _deletion_mark) " +
                "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", receipt.getId())
                .bind("number", receipt.getNumber())
                .bind("date", receipt.getDate())
                .execute());

        TestReceiptLine line = new TestReceiptLine();
        line.setProduct(product);
        line.setQuantity(qty);
        receipt.getItems().add(line);

        return receipt;
    }

    @Test
    void post_createsMovementRecords() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));

        engine.post(receipt);

        List<TestStockRegister> records = stockPersistence.getRecordsByDocument(receipt.getId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getProduct()).isEqualTo(product);
        assertThat(records.get(0).getWarehouse()).isEqualTo(warehouse);
        assertThat(records.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void post_declarativePostingRule_createsMovementRecords() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestDeclarativeReceipt receipt = createDeclarativeReceipt(warehouse, product, new BigDecimal("7"));

        engine.post(receipt);

        List<TestStockRegister> records = stockPersistence.getRecordsByDocument(receipt.getId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getProduct()).isEqualTo(product);
        assertThat(records.get(0).getWarehouse()).isEqualTo(warehouse);
        assertThat(records.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    void preview_returnsMovementsWithoutPersisting() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestDeclarativeReceipt receipt = createDeclarativeReceipt(warehouse, product, new BigDecimal("3"));

        PostingPreview preview = engine.preview(receipt);

        assertThat(preview.registers()).hasSize(1);
        assertThat(preview.registers().get(0).movements()).hasSize(1);
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId())).isEmpty();
    }

    @Test
    void post_preservesEncryptedSecretAttributesInDocumentAndRows() {
        SecretCipher cipher = new SecretCipher("posting-secret-test-key");
        TestSecretDocument document = createSecretDocument(cipher);
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(TestSecretDocument.class);
        TabularSectionDescriptor lines = descriptor.tabularSections().getFirst();
        String documentSecretColumn = descriptor.attributes().stream()
                .filter(AttributeDescriptor::secret)
                .findFirst()
                .orElseThrow()
                .columnName();
        String rowSecretColumn = lines.attributes().stream()
                .filter(AttributeDescriptor::secret)
                .findFirst()
                .orElseThrow()
                .columnName();

        String encryptedApiKeyBeforePosting = rawString(
                descriptor.tableName(), documentSecretColumn, document.getId());
        TestSecretLine line = document.getLines().getFirst();
        String encryptedNoteBeforePosting = rawString(
                lines.tableName(), rowSecretColumn, line.getId());

        engine.post(document);

        String encryptedApiKeyAfterPosting = rawString(
                descriptor.tableName(), documentSecretColumn, document.getId());
        String encryptedNoteAfterPosting = rawString(
                lines.tableName(), rowSecretColumn, line.getId());
        assertThat(encryptedApiKeyAfterPosting).isEqualTo(encryptedApiKeyBeforePosting);
        assertThat(encryptedNoteAfterPosting).isEqualTo(encryptedNoteBeforePosting);
        assertThat(cipher.decrypt(encryptedApiKeyAfterPosting)).isEqualTo(document.getApiKey());
        assertThat(cipher.decrypt(encryptedNoteAfterPosting)).isEqualTo(line.getNote());
    }

    @Test
    void post_businessRuleFailure_throws() {
        TestDeclarativeReceipt receipt = createDeclarativeReceipt(null, UUID.randomUUID(), new BigDecimal("3"));

        assertThatThrownBy(() -> engine.post(receipt))
                .isInstanceOf(su.onno.validation.ValidationException.class)
                .hasMessageContaining("Warehouse is required");
    }

    @Test
    void post_updatesBalanceTotals() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("25"));

        engine.post(receipt);

        List<Map<String, Object>> balance = stockPersistence.getBalance(Map.of(
                "product", product, "warehouse", warehouse));
        assertThat(balance).hasSize(1);
        BigDecimal qty = (BigDecimal) balance.get(0).getOrDefault("QUANTITY",
                balance.get(0).get("quantity"));
        assertThat(qty).isEqualByComparingTo(new BigDecimal("25"));
    }

    @Test
    void post_alreadyPostedDocument_rejectsWithoutDuplicatingMovements() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));

        engine.post(receipt);

        assertThatThrownBy(() -> engine.post(receipt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already posted");
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId())).hasSize(1);

        List<Map<String, Object>> balance = stockPersistence.getBalance(Map.of(
                "product", product, "warehouse", warehouse));
        BigDecimal qty = (BigDecimal) balance.get(0).getOrDefault("QUANTITY",
                balance.get(0).get("quantity"));
        assertThat(qty).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void post_deletedDocument_rejectsWithoutWritingMovements() {
        TestReceipt receipt = createReceipt(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10"));
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(TestReceipt.class);
        jdbi.useHandle(handle -> handle.createUpdate(
                        "UPDATE " + descriptor.tableName() +
                                " SET _deletion_mark = TRUE WHERE _id = :id")
                .bind("id", receipt.getId())
                .execute());

        assertThatThrownBy(() -> engine.post(receipt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId())).isEmpty();
    }

    @Test
    void repost_replacesMovementsAndTotalsAtomically() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));
        engine.post(receipt);

        receipt.getItems().getFirst().setQuantity(new BigDecimal("4"));
        engine.repost(receipt);

        List<TestStockRegister> records = stockPersistence.getRecordsByDocument(receipt.getId());
        assertThat(records).hasSize(2);
        assertThat(records).filteredOn(TestStockRegister::isActive)
                .singleElement()
                .extracting(TestStockRegister::getQuantity)
                .satisfies(quantity -> assertThat(quantity).isEqualByComparingTo("4"));
        assertStockBalance(product, warehouse, "4");
        assertThat(receipt.isPosted()).isTrue();
    }

    @Test
    void repost_failureRollsBackToOriginalPostedState() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));
        engine.post(receipt);

        receipt.getItems().getFirst().setQuantity(new BigDecimal("-20"));

        assertThatThrownBy(() -> engine.repost(receipt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative balance");
        assertStockBalance(product, warehouse, "10");
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId()))
                .singleElement()
                .matches(TestStockRegister::isActive);
        assertThat(isPosted(receipt)).isTrue();
    }

    @Test
    void post_concurrentDuplicateRequests_onlyOneWritesMovements() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));

        List<CompletableFuture<Boolean>> attempts = List.of(
                CompletableFuture.supplyAsync(() -> tryPost(receipt)),
                CompletableFuture.supplyAsync(() -> tryPost(receipt)));
        List<Boolean> results = attempts.stream().map(CompletableFuture::join).toList();

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId())).hasSize(1);
        List<Map<String, Object>> balance = stockPersistence.getBalance(Map.of(
                "product", product, "warehouse", warehouse));
        BigDecimal qty = (BigDecimal) balance.get(0).getOrDefault("QUANTITY",
                balance.get(0).get("quantity"));
        assertThat(qty).isEqualByComparingTo(new BigDecimal("10"));
    }

    private boolean tryPost(TestReceipt receipt) {
        try {
            engine.post(receipt);
            return true;
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("already posted");
            return false;
        }
    }

    @Test
    void post_defaultBalancePolicy_rejectsNegativeBalance() {
        TestReceipt receipt = createReceipt(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("-1"));

        assertThatThrownBy(() -> engine.post(receipt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative balance");
        assertThat(receipt.isPosted()).isFalse();
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId())).isEmpty();
    }

    @Test
    void post_negativeBalanceOnUntouchedDimensions_doesNotBlockPosting() {
        AccumulationRegisterDescriptor descriptor =
                registry.getRegisterDescriptor(TestStockRegister.class);
        UUID staleProduct = UUID.randomUUID();
        UUID staleWarehouse = UUID.randomUUID();
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO " + descriptor.totalsTableName() +
                                " (product, warehouse, quantity) " +
                                "VALUES (:product, :warehouse, :quantity)")
                .bind("product", staleProduct)
                .bind("warehouse", staleWarehouse)
                .bind("quantity", new BigDecimal("-1"))
                .execute());

        UUID postedProduct = UUID.randomUUID();
        UUID postedWarehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(
                postedWarehouse, postedProduct, new BigDecimal("5"));

        engine.post(receipt);

        assertStockBalance(postedProduct, postedWarehouse, "5");
        assertStockBalance(staleProduct, staleWarehouse, "-1");
        assertThat(receipt.isPosted()).isTrue();
    }

    @Test
    void post_allowNegativeBalancePolicy_permitsOverdraft() {
        TestWithdrawal withdrawal = createWithdrawal(
                UUID.randomUUID(), new BigDecimal("125.50"));

        engine.post(withdrawal);

        List<Map<String, Object>> balance =
                overdraftPersistence.getBalance(Map.of("account", withdrawal.getAccount()));
        assertThat(balance).hasSize(1);
        BigDecimal amount = (BigDecimal) balance.get(0).getOrDefault(
                "AMOUNT", balance.get(0).get("amount"));
        assertThat(amount).isEqualByComparingTo(new BigDecimal("-125.50"));
        assertThat(withdrawal.isPosted()).isTrue();
    }

    @Test
    void post_backdatedChronologicalRegister_repostsLaterDocumentsAtRepairedAverageCost() {
        UUID product = UUID.randomUUID();
        Map<UUID, DocumentObject> persisted = new HashMap<>();
        PostingEngine chronologicalEngine = new PostingEngine(
                jdbi, registry, repositoryMapFor(), null, null,
                ids -> ids.stream().map(persisted::get).filter(Objects::nonNull).toList());

        TestCostReceipt opening = createCostReceipt(
                "CR-OPEN", LocalDateTime.of(2026, 1, 1, 10, 0),
                product, new BigDecimal("10"), new BigDecimal("100"));
        chronologicalEngine.post(opening);

        TestCostIssue issue = createCostIssue(
                "CI-001", LocalDateTime.of(2026, 1, 3, 10, 0),
                product, new BigDecimal("5"));
        chronologicalEngine.post(issue);
        persisted.put(issue.getId(), issue);
        assertThat(issue.getCost()).isEqualByComparingTo("50.00");

        TestCostReceipt backdated = createCostReceipt(
                "CR-BACK", LocalDateTime.of(2026, 1, 2, 10, 0),
                product, new BigDecimal("10"), new BigDecimal("300"));
        chronologicalEngine.post(backdated);

        assertThat(issue.getCost()).isEqualByComparingTo("100.00");
        assertCostBalance(product, "15", "300.00");

        BigDecimal storedCost = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT cost FROM document_test_cost_issues WHERE _id = :id")
                .bind("id", issue.getId())
                .mapTo(BigDecimal.class)
                .one());
        assertThat(storedCost).isEqualByComparingTo("100.00");
    }

    @Test
    void unpost_backdatedChronologicalRegister_restoresLaterDocumentsWithoutRemovedMovement() {
        UUID product = UUID.randomUUID();
        Map<UUID, DocumentObject> persisted = new HashMap<>();
        PostingEngine chronologicalEngine = new PostingEngine(
                jdbi, registry, repositoryMapFor(), null, null,
                ids -> ids.stream().map(persisted::get).filter(Objects::nonNull).toList());

        TestCostReceipt opening = createCostReceipt(
                "CR-OPEN", LocalDateTime.of(2026, 1, 1, 10, 0),
                product, new BigDecimal("10"), new BigDecimal("100"));
        chronologicalEngine.post(opening);
        TestCostReceipt backdated = createCostReceipt(
                "CR-BACK", LocalDateTime.of(2026, 1, 2, 10, 0),
                product, new BigDecimal("10"), new BigDecimal("300"));
        chronologicalEngine.post(backdated);
        TestCostIssue issue = createCostIssue(
                "CI-001", LocalDateTime.of(2026, 1, 3, 10, 0),
                product, new BigDecimal("5"));
        chronologicalEngine.post(issue);
        persisted.put(issue.getId(), issue);
        assertThat(issue.getCost()).isEqualByComparingTo("100.00");

        chronologicalEngine.unpost(backdated);

        assertThat(backdated.isPosted()).isFalse();
        assertThat(issue.getCost()).isEqualByComparingTo("50.00");
        assertCostBalance(product, "5", "50.00");
    }

    @Test
    void post_setsDocumentPostedTrue() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("5"));

        engine.post(receipt);

        assertThat(receipt.isPosted()).isTrue();

        // Verify in DB
        DocumentDescriptor docDesc = registry.getDocumentDescriptor(TestReceipt.class);
        boolean posted = jdbi.withHandle(h ->
                h.createQuery("SELECT _posted FROM " + docDesc.tableName() + " WHERE _id = :id")
                        .bind("id", receipt.getId())
                        .mapTo(Boolean.class)
                        .one());
        assertThat(posted).isTrue();
    }

    @Test
    void unpost_deactivatesRecords() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));

        engine.post(receipt);
        engine.unpost(receipt);

        List<TestStockRegister> records = stockPersistence.getRecordsByDocument(receipt.getId());
        assertThat(records).allMatch(r -> !r.isActive());
    }

    @Test
    void unpost_reversesTotals() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("15"));

        engine.post(receipt);
        engine.unpost(receipt);

        List<Map<String, Object>> balance = stockPersistence.getBalance(Map.of(
                "product", product, "warehouse", warehouse));
        assertThat(balance).hasSize(1);
        BigDecimal qty = (BigDecimal) balance.get(0).getOrDefault("QUANTITY",
                balance.get(0).get("quantity"));
        assertThat(qty).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void unpost_draftDocumentRejectsWithoutPublishingEvent() {
        TestReceipt receipt = createReceipt(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10"));
        List<Object> events = new ArrayList<>();
        PostingEngine engineWithEvents = new PostingEngine(
                jdbi, registry, repositoryMapFor(), null, events::add);

        assertThatThrownBy(() -> engineWithEvents.unpost(receipt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not posted");
        assertThat(events).isEmpty();
    }

    @Test
    void post_nonPostableDocument_throws() {
        DocumentObject nonPostable = new DocumentObject() {};
        nonPostable.setId(UUID.randomUUID());
        nonPostable.setDate(LocalDateTime.now());

        assertThatThrownBy(() -> engine.post(nonPostable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement Postable");
    }

    @Test
    void post_and_unpost_publishApplicationEvents() {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        TestReceipt receipt = createReceipt(warehouse, product, new BigDecimal("10"));

        List<Object> events = new ArrayList<>();
        PostingEngine engineWithEvents = new PostingEngine(
                jdbi, registry, repositoryMapFor(), null, events::add);

        engineWithEvents.post(receipt);
        engineWithEvents.unpost(receipt);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(DocumentPostedEvent.class);
        DocumentPostedEvent posted = (DocumentPostedEvent) events.get(0);
        assertThat(posted.document()).isSameAs(receipt);
        assertThat(posted.documentId()).isEqualTo(receipt.getId());

        assertThat(events.get(1)).isInstanceOf(DocumentUnpostedEvent.class);
        assertThat(((DocumentUnpostedEvent) events.get(1)).document()).isSameAs(receipt);
    }

    @Test
    void post_businessRuleFailure_publishesNoEvent() {
        List<Object> events = new ArrayList<>();
        PostingEngine engineWithEvents = new PostingEngine(
                jdbi, registry, repositoryMapFor(), null, events::add);
        TestDeclarativeReceipt receipt = createDeclarativeReceipt(null, UUID.randomUUID(), new BigDecimal("3"));

        assertThatThrownBy(() -> engineWithEvents.post(receipt))
                .isInstanceOf(su.onno.validation.ValidationException.class);
        assertThat(events).isEmpty();
    }

    @Test
    void post_rollsBackWhenOutboxAppendFails() {
        TestReceipt receipt = createReceipt(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10"));
        jdbi.useHandle(handle -> handle.execute("DROP TABLE onno_outbox"));
        PostingEngine engineWithOutbox = new PostingEngine(
                jdbi, registry, repositoryMapFor(), new OutboxWriter(jdbi));

        assertThatThrownBy(() -> engineWithOutbox.post(receipt))
                .hasMessageContaining("onno_outbox");

        assertThat(isPosted(receipt)).isFalse();
        assertThat(stockPersistence.getRecordsByDocument(receipt.getId())).isEmpty();
    }

    private Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMapFor() {
        RegisterRepositoryImpl<TestStockRegister> stockRepo =
                new RegisterRepositoryImpl<>(stockPersistence, TestStockRegister.class);
        Map<Class<?>, RegisterRepositoryImpl<?>> map = new HashMap<>();
        map.put(TestStockRegister.class, stockRepo);
        map.put(TestOverdraftRegister.class,
                new RegisterRepositoryImpl<>(overdraftPersistence, TestOverdraftRegister.class));
        map.put(TestCostRegister.class,
                new RegisterRepositoryImpl<>(costPersistence, TestCostRegister.class));
        return map;
    }

    private boolean isPosted(DocumentObject document) {
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(document.getClass());
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT _posted FROM " + descriptor.tableName() + " WHERE _id = :id")
                .bind("id", document.getId())
                .mapTo(Boolean.class)
                .one());
    }

    private void assertStockBalance(UUID product, UUID warehouse, String expected) {
        List<Map<String, Object>> balance = stockPersistence.getBalance(Map.of(
                "product", product, "warehouse", warehouse));
        assertThat(balance).hasSize(1);
        BigDecimal quantity = (BigDecimal) balance.getFirst().getOrDefault(
                "QUANTITY", balance.getFirst().get("quantity"));
        assertThat(quantity).isEqualByComparingTo(expected);
    }

    private TestSecretDocument createSecretDocument(SecretCipher cipher) {
        TestSecretDocument document = new TestSecretDocument();
        document.setId(UUID.randomUUID());
        document.setNumber("SECRET-001");
        document.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        document.setApiKey("document-plaintext-secret");

        TestSecretLine line = new TestSecretLine();
        line.setId(UUID.randomUUID());
        line.setLineNumber(1);
        line.setNote("row-plaintext-secret");
        document.getLines().add(line);

        DocumentDescriptor descriptor = registry.getDocumentDescriptor(TestSecretDocument.class);
        AttributeDescriptor documentSecret = descriptor.attributes().stream()
                .filter(AttributeDescriptor::secret)
                .findFirst()
                .orElseThrow();
        TabularSectionDescriptor lines = descriptor.tabularSections().getFirst();
        AttributeDescriptor rowSecret = lines.attributes().stream()
                .filter(AttributeDescriptor::secret)
                .findFirst()
                .orElseThrow();

        jdbi.useHandle(handle -> {
            handle.createUpdate(
                            "INSERT INTO " + descriptor.tableName() +
                                    " (_id, _number, _date, _posted, _deletion_mark, " +
                                    documentSecret.columnName() + ") VALUES " +
                                    "(:id, :number, :date, FALSE, FALSE, :secret)")
                    .bind("id", document.getId())
                    .bind("number", document.getNumber())
                    .bind("date", document.getDate())
                    .bind("secret", cipher.encrypt(document.getApiKey()))
                    .execute();
            handle.createUpdate(
                            "INSERT INTO " + lines.tableName() +
                                    " (_id, _parent_id, _line_number, " + rowSecret.columnName() +
                                    ") VALUES (:id, :parentId, :lineNumber, :secret)")
                    .bind("id", line.getId())
                    .bind("parentId", document.getId())
                    .bind("lineNumber", line.getLineNumber())
                    .bind("secret", cipher.encrypt(line.getNote()))
                    .execute();
        });
        return document;
    }

    private String rawString(String table, String column, UUID id) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT " + column + " FROM " + table + " WHERE _id = :id")
                .bind("id", id)
                .mapTo(String.class)
                .one());
    }

    private TestCostReceipt createCostReceipt(String number,
                                              LocalDateTime date,
                                              UUID product,
                                              BigDecimal quantity,
                                              BigDecimal amount) {
        TestCostReceipt receipt = new TestCostReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber(number);
        receipt.setDate(date);
        receipt.setProduct(product);
        receipt.setQuantity(quantity);
        receipt.setAmount(amount);
        insertCostDocument(receipt, product, quantity, amount);
        return receipt;
    }

    private TestCostIssue createCostIssue(String number,
                                          LocalDateTime date,
                                          UUID product,
                                          BigDecimal quantity) {
        TestCostIssue issue = new TestCostIssue();
        issue.setId(UUID.randomUUID());
        issue.setNumber(number);
        issue.setDate(date);
        issue.setProduct(product);
        issue.setQuantity(quantity);
        insertCostDocument(issue, product, quantity, null);
        return issue;
    }

    private void insertCostDocument(DocumentObject document,
                                    UUID product,
                                    BigDecimal quantity,
                                    BigDecimal amountOrCost) {
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(document.getClass());
        String amountColumn = document instanceof TestCostReceipt ? "amount" : "cost";
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO " + descriptor.tableName() +
                                " (_id, _number, _date, _posted, _deletion_mark, product, quantity, " +
                                amountColumn + ") VALUES " +
                                "(:id, :number, :date, FALSE, FALSE, :product, :quantity, :amount)")
                .bind("id", document.getId())
                .bind("number", document.getNumber())
                .bind("date", document.getDate())
                .bind("product", product)
                .bind("quantity", quantity)
                .bind("amount", amountOrCost)
                .execute());
    }

    private void assertCostBalance(UUID product, String quantity, String amount) {
        List<Map<String, Object>> balance =
                costPersistence.getBalance(Map.of("product", product));
        assertThat(balance).hasSize(1);
        Map<String, Object> row = balance.get(0);
        assertThat((BigDecimal) row.getOrDefault("QUANTITY", row.get("quantity")))
                .isEqualByComparingTo(quantity);
        assertThat((BigDecimal) row.getOrDefault("AMOUNT", row.get("amount")))
                .isEqualByComparingTo(amount);
    }

    private TestWithdrawal createWithdrawal(UUID account, BigDecimal amount) {
        TestWithdrawal withdrawal = new TestWithdrawal();
        withdrawal.setId(UUID.randomUUID());
        withdrawal.setNumber("WD-001");
        withdrawal.setDate(LocalDateTime.of(2026, 3, 15, 11, 0));
        withdrawal.setAccount(account);
        withdrawal.setAmount(amount);

        DocumentDescriptor docDesc = registry.getDocumentDescriptor(TestWithdrawal.class);
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + docDesc.tableName() +
                                " (_id, _number, _date, _posted, _deletion_mark, account, amount) " +
                                "VALUES (:id, :number, :date, FALSE, FALSE, :account, :amount)")
                .bind("id", withdrawal.getId())
                .bind("number", withdrawal.getNumber())
                .bind("date", withdrawal.getDate())
                .bind("account", withdrawal.getAccount())
                .bind("amount", withdrawal.getAmount())
                .execute());
        return withdrawal;
    }

    @Test
    void post_multipleLineItems_createsMultipleRecords() {
        UUID product1 = UUID.randomUUID();
        UUID product2 = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();

        TestReceipt receipt = new TestReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("REC-MULTI");
        receipt.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        receipt.setWarehouse(warehouse);

        DocumentDescriptor docDesc = registry.getDocumentDescriptor(TestReceipt.class);
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO " + docDesc.tableName() + " (_id, _number, _date, _posted, _deletion_mark) " +
                "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", receipt.getId())
                .bind("number", receipt.getNumber())
                .bind("date", receipt.getDate())
                .execute());

        TestReceiptLine line1 = new TestReceiptLine();
        line1.setProduct(product1);
        line1.setQuantity(new BigDecimal("10"));
        TestReceiptLine line2 = new TestReceiptLine();
        line2.setProduct(product2);
        line2.setQuantity(new BigDecimal("20"));
        receipt.getItems().add(line1);
        receipt.getItems().add(line2);

        engine.post(receipt);

        List<TestStockRegister> records = stockPersistence.getRecordsByDocument(receipt.getId());
        assertThat(records).hasSize(2);
    }

    @Test
    void post_concurrentPosts_doNotCrossContaminateMovements() throws Exception {
        // Regression for #148: movements buffered on the shared singleton
        // RegisterRepositoryImpl.pendingMovements, so concurrent posts appended to / iterated /
        // cleared one list at once — ConcurrentModificationException while a post iterated its
        // movements, and movements leaking between unrelated documents. Each post must own its
        // movement buffer. Each document here touches a distinct product (a distinct totals key),
        // so this isolates the shared-buffer bug from the totals-upsert key race.
        UUID warehouse = UUID.randomUUID();
        int docCount = 50;
        int threads = 8;

        List<TestReceipt> receipts = new ArrayList<>();
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            UUID product = UUID.randomUUID();
            products.add(product);
            receipts.add(createNumberedReceipt("CONC-" + i, warehouse, product, BigDecimal.ONE));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<Void>> futures = receipts.stream()
                    .map(r -> CompletableFuture.runAsync(() -> engine.post(r), pool))
                    .toList();
            // Joining surfaces any per-post exception (CME / cross-inserted movement) as a failure.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Exactly docCount movement rows total — no double-inserts, no leaked movements.
        long movementRows = jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM register_test_stock WHERE _active = TRUE")
                .mapTo(Long.class).one());
        assertThat(movementRows).isEqualTo(docCount);

        // Each document posted exactly its own single movement, for its own product.
        for (int i = 0; i < docCount; i++) {
            List<TestStockRegister> records = stockPersistence.getRecordsByDocument(receipts.get(i).getId());
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getProduct()).isEqualTo(products.get(i));
            assertThat(records.get(0).getQuantity()).isEqualByComparingTo(BigDecimal.ONE);
        }

        assertThat(receipts).allMatch(TestReceipt::isPosted);
    }

    private TestReceipt createNumberedReceipt(String number, UUID warehouse, UUID product, BigDecimal qty) {
        TestReceipt receipt = new TestReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber(number);
        receipt.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        receipt.setWarehouse(warehouse);

        DocumentDescriptor docDesc = registry.getDocumentDescriptor(TestReceipt.class);
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + docDesc.tableName() + " (_id, _number, _date, _posted, _deletion_mark) " +
                                "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", receipt.getId())
                .bind("number", receipt.getNumber())
                .bind("date", receipt.getDate())
                .execute());

        TestReceiptLine line = new TestReceiptLine();
        line.setProduct(product);
        line.setQuantity(qty);
        receipt.getItems().add(line);
        return receipt;
    }
}
