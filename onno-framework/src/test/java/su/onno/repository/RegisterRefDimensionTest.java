package su.onno.repository;

import su.onno.fixtures.TestProduct;
import su.onno.fixtures.TestRefSalesRegister;
import su.onno.fixtures.TestRefStockRegister;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Document;
import su.onno.annotations.Enumeration;
import su.onno.annotations.RefTargets;
import su.onno.annotations.Resource;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.DocumentObject;
import su.onno.model.MovementType;
import su.onno.posting.RegisterPersistence;
import su.onno.schema.SchemaGenerator;
import su.onno.types.PolyRef;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Register dimensions whose Java values need conversion to/from their storage representation.
 *
 * <p>Issue #207: the typed register reads ({@code getBalance}/{@code getTurnover}) blew up on a
 * {@code Ref<T>} dimension — the row mapper asked JDBC to convert the stored UUID column straight
 * to {@code Ref} ("converting to class su.onno.types.Ref"). The mapper must rebuild the Ref from
 * the UUID against the field's declared target type, the way the write path unwraps it.</p>
 *
 * <p>Issue #288: enum dimensions scanned and generated as UUID columns but the posting binder sent
 * the Java enum object. Movement/totals writes, filters, and typed reads must use the enum's stable
 * UUID representation.</p>
 */
class RegisterRefDimensionTest {

    @Enumeration(name = "TestRegisterChannels")
    public enum Channel {
        RETAIL,
        WHOLESALE
    }

    @Document(name = "TestRegisterSources")
    public static class RegisterSource extends DocumentObject {
    }

    @AccumulationRegister(name = "TestChannelBalances", type = AccumulationType.BALANCE)
    public static class ChannelBalance extends AccumulationRecord {
        @Dimension
        private Channel channel;

        @Resource
        private BigDecimal amount;

        public Channel getChannel() {
            return channel;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    @AccumulationRegister(name = "TestPolyBalances", type = AccumulationType.BALANCE)
    public static class PolyBalance extends AccumulationRecord {
        @Dimension
        @RefTargets({TestProduct.class, RegisterSource.class})
        private PolyRef owner;

        @Resource
        private BigDecimal amount;

        public PolyRef getOwner() {
            return owner;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    private Jdbi jdbi;
    private RegisterRepositoryImpl<TestRefStockRegister> stock;
    private RegisterRepositoryImpl<TestRefSalesRegister> sales;

    private final UUID productA = UUID.randomUUID();
    private final UUID productB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        AccumulationRegisterDescriptor stockDesc = scanner.scanRegister(TestRefStockRegister.class);
        AccumulationRegisterDescriptor salesDesc = scanner.scanRegister(TestRefSalesRegister.class);
        registry.registerAccumulation(stockDesc);
        registry.registerAccumulation(salesDesc);

        new SchemaGenerator(registry).execute(jdbi);
        stock = new RegisterRepositoryImpl<>(new RegisterPersistence<>(jdbi, stockDesc), TestRefStockRegister.class);
        sales = new RegisterRepositoryImpl<>(new RegisterPersistence<>(jdbi, salesDesc), TestRefSalesRegister.class);
    }

    private void stockMovement(UUID product, String currency, String qty) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO register_test_ref_stock " +
                                "(_id, _period, _active, _document_ref, _movement_type, product, currency, quantity) " +
                                "VALUES (:id, :period, TRUE, :doc, :type, :product, :currency, :qty)")
                .bind("id", UUID.randomUUID()).bind("period", LocalDateTime.of(2026, 1, 5, 8, 0))
                .bind("doc", UUID.randomUUID()).bind("type", MovementType.RECEIPT.name())
                .bind("product", product).bind("currency", currency)
                .bind("qty", new BigDecimal(qty)).execute());
    }

    private void salesMovement(UUID product, String amount) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO register_test_ref_sales " +
                                "(_id, _period, _active, _document_ref, _movement_type, product, amount) " +
                                "VALUES (:id, :period, TRUE, :doc, :type, :product, :amount)")
                .bind("id", UUID.randomUUID()).bind("period", LocalDateTime.of(2026, 1, 5, 8, 0))
                .bind("doc", UUID.randomUUID()).bind("type", MovementType.RECEIPT.name())
                .bind("product", product)
                .bind("amount", new BigDecimal(amount)).execute());
    }

    @Test
    void getBalance_mapsRefDimensionBackToTypedRef() {
        stockMovement(productA, "EUR", "10");
        stockMovement(productB, "USD", "4");
        stock.rebuildTotals();

        List<TestRefStockRegister> rows = stock.getBalance();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(Ref.of(TestProduct.class, productA));
            assertThat(r.getCurrency()).isEqualTo("EUR");
            assertThat(r.getQuantity()).isEqualByComparingTo("10");
        });
        assertThat(rows).allMatch(r -> r.getProduct().type() == TestProduct.class);
    }

    @Test
    void getBalance_filteredByRefValue_stillMaps() {
        stockMovement(productA, "EUR", "10");
        stockMovement(productB, "USD", "4");
        stock.rebuildTotals();

        List<TestRefStockRegister> rows =
                stock.getBalance(f -> f.where(TestRefStockRegister::getProduct, Ref.of(TestProduct.class, productA)));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getProduct().id()).isEqualTo(productA);
    }

    @Test
    void getTurnover_mapsRefDimensionBackToTypedRef() {
        salesMovement(productA, "100");
        salesMovement(productA, "50");
        salesMovement(productB, "7");

        List<TestRefSalesRegister> rows = sales.getTurnover(
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 0, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(Ref.of(TestProduct.class, productA));
            assertThat(r.getAmount()).isEqualByComparingTo("150");
        });
    }

    @Test
    void getBalance_mapsAndFiltersPolymorphicDimension() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        AccumulationRegisterDescriptor descriptor = scanner.scanRegister(PolyBalance.class);
        registry.registerAccumulation(descriptor);
        new SchemaGenerator(registry).execute(jdbi);
        RegisterRepositoryImpl<PolyBalance> repository = new RegisterRepositoryImpl<>(
                new RegisterPersistence<>(jdbi, descriptor), PolyBalance.class);
        PolyRef productOwner = PolyRef.of(TestProduct.class, productA);
        PolyRef documentOwner = PolyRef.of(RegisterSource.class, UUID.randomUUID());

        for (PolyRef owner : List.of(productOwner, documentOwner)) {
            jdbi.useHandle(h -> h.createUpdate(
                            "INSERT INTO register_test_poly_balances "
                                    + "(_id, _period, _active, _document_ref, _movement_type, owner, amount) "
                                    + "VALUES (:id, :period, TRUE, :doc, :type, :owner, :amount)")
                    .bind("id", UUID.randomUUID())
                    .bind("period", LocalDateTime.of(2026, 1, 5, 8, 0))
                    .bind("doc", UUID.randomUUID())
                    .bind("type", MovementType.RECEIPT.name())
                    .bind("owner", owner.externalForm())
                    .bind("amount", new BigDecimal("25"))
                    .execute());
        }
        repository.rebuildTotals();

        assertThat(repository.getBalance())
                .extracting(PolyBalance::getOwner)
                .containsExactlyInAnyOrder(productOwner, documentOwner);
        assertThat(repository.getBalance(f -> f.where(PolyBalance::getOwner, documentOwner)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getOwner()).isEqualTo(documentOwner);
                    assertThat(row.getAmount()).isEqualByComparingTo("25");
                });
    }

    @Test
    void postingBindsEnumDimensionAsItsStableUuid() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        AccumulationRegisterDescriptor descriptor = scanner.scanRegister(ChannelBalance.class);
        registry.registerEnumeration(scanner.scanEnumeration(Channel.class));
        registry.registerAccumulation(descriptor);
        new SchemaGenerator(registry).execute(jdbi);

        RegisterPersistence<ChannelBalance> persistence = new RegisterPersistence<>(jdbi, descriptor);
        ChannelBalance movement = new ChannelBalance();
        movement.channel = Channel.RETAIL;
        movement.amount = new BigDecimal("12.50");
        movement.setMovementType(MovementType.RECEIPT);

        UUID documentRef = UUID.randomUUID();
        LocalDateTime period = LocalDateTime.of(2026, 1, 5, 8, 0);
        jdbi.useHandle(handle -> {
            persistence.insertRecords(handle, List.of(movement), documentRef, period);
            persistence.updateTotals(handle, List.of(movement));
        });

        UUID expected = EnumerationPersistence.resolveId(Channel.class, Channel.RETAIL);
        jdbi.useHandle(handle -> {
            assertThat(handle.createQuery("SELECT channel FROM " + descriptor.tableName())
                    .mapTo(UUID.class)
                    .one()).isEqualTo(expected);
            assertThat(handle.createQuery("SELECT channel FROM " + descriptor.totalsTableName())
                    .mapTo(UUID.class)
                    .one()).isEqualTo(expected);
        });

        RegisterRepositoryImpl<ChannelBalance> repository = new RegisterRepositoryImpl<>(
                persistence, ChannelBalance.class);
        assertThat(repository.getBalance(
                filter -> filter.where(ChannelBalance::getChannel, Channel.RETAIL)))
                .singleElement()
                .satisfies(balance -> {
                    assertThat(balance.getChannel()).isEqualTo(Channel.RETAIL);
                    assertThat(balance.getAmount()).isEqualByComparingTo("12.50");
                });
    }
}
