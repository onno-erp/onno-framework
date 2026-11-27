package su.onno.posting;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.fixtures.TestCostRegister;
import su.onno.fixtures.TestStockRegister;
import su.onno.lifecycle.BeforePostHandler;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.Postable;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.repository.RegisterRepositoryImpl;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.schema.SchemaGenerator;
import su.onno.validation.ValidationException;

import lombok.Getter;
import lombok.Setter;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Posting may change movements and posting state, but must never save the aggregate (#560). */
class PostingWriteBoundaryTest {

    enum Operation { POST, REPOST, PREVIEW }
    enum BackdatedOperation { POST, REPOST, UNPOST }

    @Getter
    @Setter
    @Document(name = "WriteBoundaryDocuments")
    public static class BoundaryDocument extends DocumentObject
            implements BeforeWriteHandler, BeforePostHandler, Postable, Validated {
        @Attribute private String memo;
        @Attribute private UUID product;
        @Attribute private BigDecimal quantity;
        @TabularSection private List<BoundaryLine> lines = new ArrayList<>();
        private boolean chronological;
        private boolean vetoPosting;
        private boolean invalid;
        private int beforeWriteCalls;
        private int beforePostCalls;

        @Override
        public void beforeWrite() {
            beforeWriteCalls++;
        }

        @Override
        public void beforePost() {
            beforePostCalls++;
            memo = "temporary beforePost value";
            if (vetoPosting) throw new IllegalStateException("Posting vetoed");
        }

        @Override
        public List<BusinessRule> rules() {
            return List.of(new BusinessRule("allowed", "Posting rule rejected", () -> !invalid));
        }

        @Override
        public void handlePosting(PostingContext context) {
            memo = "temporary handlePosting value";
            lines.getFirst().setNote("temporary posting line");
            if (chronological) {
                context.movements(TestCostRegister.class).addReceipt(row -> {
                    row.setProduct(product);
                    row.setQuantity(quantity);
                    row.setAmount(quantity.multiply(BigDecimal.valueOf(2)));
                });
            } else {
                context.movements(TestStockRegister.class).addReceipt(row -> {
                    row.setProduct(product);
                    row.setWarehouse(product);
                    row.setQuantity(quantity);
                });
            }
        }
    }

    @Getter
    @Setter
    public static class BoundaryLine extends TabularSectionRow {
        @Attribute private String note;
    }

    private Jdbi jdbi;
    private PostingEngine engine;
    private DocumentDescriptor descriptor;
    private RegisterPersistence<TestStockRegister> stock;
    private RegisterPersistence<TestCostRegister> cost;
    private final Map<UUID, DocumentObject> documents = new HashMap<>();
    private final List<Object> events = new ArrayList<>();
    private final UUID product = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(dataSource);
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        descriptor = scanner.scanDocument(BoundaryDocument.class);
        registry.registerDocument(descriptor);
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));
        registry.registerAccumulation(scanner.scanRegister(TestCostRegister.class));
        new SchemaGenerator(registry).execute(jdbi);
        stock = new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(TestStockRegister.class));
        cost = new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(TestCostRegister.class));
        engine = new PostingEngine(jdbi, registry, Map.of(
                TestStockRegister.class, new RegisterRepositoryImpl<>(stock, TestStockRegister.class),
                TestCostRegister.class, new RegisterRepositoryImpl<>(cost, TestCostRegister.class)),
                null, events::add, ids -> ids.stream().map(documents::get).toList());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void postingRunsPostingHooksButNeverBeforeWrite(Operation operation) {
        BoundaryDocument document = storedDocument(1, false);
        prepare(operation, document);

        run(operation, document);

        assertThat(document.getBeforeWriteCalls()).isZero();
        assertThat(document.getBeforePostCalls()).isEqualTo(1);
        if (operation == Operation.PREVIEW) {
            assertThat(stock.getRecordsByDocument(document.getId())).isEmpty();
            assertThat(events).isEmpty();
        } else {
            assertThat(stock.getRecordsByDocument(document.getId()).stream()
                    .filter(TestStockRegister::isActive).toList()).singleElement()
                    .satisfies(row -> assertThat(row.getQuantity()).isEqualByComparingTo("5"));
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void postingDoesNotOverwriteSavedHeaderLinesOrVersionEvenWithAStaleObject(Operation operation) {
        BoundaryDocument document = storedDocument(1, false);
        prepare(operation, document);
        // Another writer committed after this object was loaded. Posting must not overwrite it.
        jdbi.useHandle(handle -> {
            handle.createUpdate("UPDATE " + descriptor.tableName()
                            + " SET memo = 'concurrent edit', _version = 8 WHERE _id = :id")
                    .bind("id", document.getId()).execute();
            handle.createUpdate("UPDATE " + lineTable() + " SET note = 'concurrent line' WHERE _id = :id")
                    .bind("id", document.getLines().getFirst().getId()).execute();
        });
        var before = storedValues(document);

        run(operation, document);

        assertThat(storedValues(document)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void postingRulesStillRejectWithoutWriting(Operation operation) {
        BoundaryDocument document = storedDocument(1, false);
        prepare(operation, document);
        document.setInvalid(true);
        var before = storedValues(document);

        assertThatThrownBy(() -> run(operation, document)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Posting rule rejected");

        assertThat(storedValues(document)).isEqualTo(before);
        assertThat(document.getBeforeWriteCalls()).isZero();
        assertThat(events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void beforePostCanStillVetoWithoutWriting(Operation operation) {
        BoundaryDocument document = storedDocument(1, false);
        prepare(operation, document);
        document.setVetoPosting(true);
        var before = storedValues(document);

        assertThatThrownBy(() -> run(operation, document)).hasMessage("Posting vetoed");

        assertThat(storedValues(document)).isEqualTo(before);
        assertThat(events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(BackdatedOperation.class)
    void chronologicalRestorationNeverSavesRequestedOrLaterDocument(BackdatedOperation operation) {
        BoundaryDocument requested = storedDocument(1, true);
        if (operation != BackdatedOperation.POST) engine.post(requested);
        BoundaryDocument later = storedDocument(3, true);
        engine.post(later);
        requested.setBeforeWriteCalls(0);
        later.setBeforeWriteCalls(0);
        later.setBeforePostCalls(0);
        var requestedBefore = storedValues(requested);
        var laterBefore = storedValues(later);

        switch (operation) {
            case POST -> engine.post(requested);
            case REPOST -> engine.repost(requested);
            case UNPOST -> engine.unpost(requested);
        }

        assertThat(requested.getBeforeWriteCalls()).isZero();
        assertThat(later.getBeforeWriteCalls()).isZero();
        assertThat(later.getBeforePostCalls()).isEqualTo(1);
        assertThat(storedValues(requested)).isEqualTo(requestedBefore);
        assertThat(storedValues(later)).isEqualTo(laterBefore);
        assertThat(cost.getRecordsByDocument(later.getId()).stream()
                .filter(TestCostRegister::isActive).toList()).hasSize(1);
        assertThat(requested.isPosted()).isEqualTo(operation != BackdatedOperation.UNPOST);
    }

    @Test
    void rejectedChronologicalRestorationRollsBackPostingAndKeepsStoredValues() {
        BoundaryDocument later = storedDocument(3, true);
        engine.post(later);
        BoundaryDocument requested = storedDocument(1, true);
        requested.setQuantity(new BigDecimal("-100"));
        var before = storedValues(later);
        events.clear();

        assertThatThrownBy(() -> engine.post(requested)).hasMessageContaining("negative balance");

        assertThat(storedValues(later)).isEqualTo(before);
        assertThat(cost.getRecordsByDocument(requested.getId())).isEmpty();
        assertThat(cost.getRecordsByDocument(later.getId()).stream()
                .filter(TestCostRegister::isActive).toList()).hasSize(1);
        assertThat(events).isEmpty();
        assertThat(requested.isPosted()).isFalse();
    }

    private void prepare(Operation operation, BoundaryDocument document) {
        if (operation == Operation.REPOST) engine.post(document);
        document.setBeforeWriteCalls(0);
        document.setBeforePostCalls(0);
        events.clear();
    }

    private void run(Operation operation, BoundaryDocument document) {
        switch (operation) {
            case POST -> engine.post(document);
            case REPOST -> engine.repost(document);
            case PREVIEW -> {
                PostingPreview preview = engine.preview(document);
                assertThat(preview.registers()).singleElement()
                        .satisfies(register -> assertThat(register.movements()).hasSize(1));
            }
        }
    }

    private BoundaryDocument storedDocument(int day, boolean chronological) {
        BoundaryDocument document = new BoundaryDocument();
        document.setId(UUID.randomUUID());
        document.setNumber("BOUNDARY-" + day);
        document.setDate(LocalDateTime.of(2026, 1, day, 10, 0));
        document.setVersion(7);
        document.setMemo("saved header");
        document.setProduct(product);
        document.setQuantity(new BigDecimal("5"));
        document.setChronological(chronological);
        BoundaryLine line = new BoundaryLine();
        line.setId(UUID.randomUUID());
        line.setNote("saved line");
        document.getLines().add(line);
        jdbi.useHandle(handle -> {
            handle.createUpdate("INSERT INTO " + descriptor.tableName()
                            + " (_id, _number, _date, _posted, _deletion_mark, _version, memo, product, quantity)"
                            + " VALUES (:id, :number, :date, FALSE, FALSE, 7, :memo, :product, :quantity)")
                    .bind("id", document.getId()).bind("number", document.getNumber())
                    .bind("date", document.getDate()).bind("memo", document.getMemo())
                    .bind("product", product).bind("quantity", document.getQuantity()).execute();
            handle.createUpdate("INSERT INTO " + lineTable()
                            + " (_id, _parent_id, _line_number, note) VALUES (:id, :owner, 1, :note)")
                    .bind("id", line.getId()).bind("owner", document.getId()).bind("note", line.getNote()).execute();
        });
        documents.put(document.getId(), document);
        return document;
    }

    private String lineTable() {
        return descriptor.tabularSections().getFirst().tableName();
    }

    private List<Map<String, Object>> storedValues(BoundaryDocument document) {
        return jdbi.withHandle(handle -> {
            Map<String, Object> header = handle.createQuery("SELECT * FROM " + descriptor.tableName()
                            + " WHERE _id = :id").bind("id", document.getId()).mapToMap().one();
            header.remove("_posted"); // The only document column a successful posting may change.
            Map<String, Object> line = handle.createQuery("SELECT * FROM " + lineTable() + " WHERE _id = :id")
                    .bind("id", document.getLines().getFirst().getId()).mapToMap().one();
            return List.of(header, line);
        });
    }
}
