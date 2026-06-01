package com.onec.posting;

import com.onec.fixtures.TestDeclarativeReceipt;
import com.onec.fixtures.TestReceipt;
import com.onec.fixtures.TestReceiptLine;
import com.onec.fixtures.TestStockRegister;
import com.onec.metadata.*;
import com.onec.model.DocumentObject;
import com.onec.repository.RegisterRepositoryImpl;
import com.onec.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PostingTest {

    private Jdbi jdbi;
    private PostingEngine engine;
    private RegisterPersistence<TestStockRegister> stockPersistence;
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
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));

        SchemaGenerator schema = new SchemaGenerator(registry);
        schema.execute(jdbi);

        AccumulationRegisterDescriptor stockDesc = registry.getRegisterDescriptor(TestStockRegister.class);
        stockPersistence = new RegisterPersistence<>(jdbi, stockDesc);

        RegisterRepositoryImpl<TestStockRegister> stockRepo =
                new RegisterRepositoryImpl<>(stockPersistence, TestStockRegister.class);

        Map<Class<?>, RegisterRepositoryImpl<?>> repositoryMap = new HashMap<>();
        repositoryMap.put(TestStockRegister.class, stockRepo);

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
    void post_businessRuleFailure_throws() {
        TestDeclarativeReceipt receipt = createDeclarativeReceipt(null, UUID.randomUUID(), new BigDecimal("3"));

        assertThatThrownBy(() -> engine.post(receipt))
                .isInstanceOf(IllegalStateException.class)
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
    void post_nonPostableDocument_throws() {
        DocumentObject nonPostable = new DocumentObject() {};
        nonPostable.setId(UUID.randomUUID());
        nonPostable.setDate(LocalDateTime.now());

        assertThatThrownBy(() -> engine.post(nonPostable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not implement Postable");
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
}
