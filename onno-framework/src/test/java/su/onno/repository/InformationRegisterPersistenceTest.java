package su.onno.repository;

import su.onno.fixtures.TestPriceRegister;
import su.onno.fixtures.TestProduct;
import su.onno.fixtures.TestSettingRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Enumeration;
import su.onno.annotations.InformationRegister;
import su.onno.annotations.Resource;
import su.onno.metadata.*;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class InformationRegisterPersistenceTest {

    @Enumeration(name = "TestInformationChannels")
    enum Channel {
        RETAIL,
        WHOLESALE
    }

    @InformationRegister(name = "TypedInformationRates", periodicity = Periodicity.NONE)
    public static class TypedInformationRate extends InformationRecord {
        @Dimension
        private Ref<TestProduct> product;

        @Dimension
        private Channel channel;

        @Resource
        private BigDecimal rate;

        public Ref<TestProduct> getProduct() {
            return product;
        }

        public void setProduct(Ref<TestProduct> product) {
            this.product = product;
        }

        public Channel getChannel() {
            return channel;
        }

        public void setChannel(Channel channel) {
            this.channel = channel;
        }

        public BigDecimal getRate() {
            return rate;
        }

        public void setRate(BigDecimal rate) {
            this.rate = rate;
        }
    }

    @InformationRegister(name = "TaskStatusHistory", periodicity = Periodicity.SECOND)
    public static class TaskStatusHistory extends InformationRecord {
        @Dimension
        private UUID task;

        @su.onno.annotations.Attribute
        private String status;

        public UUID getTask() {
            return task;
        }

        public void setTask(UUID task) {
            this.task = task;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    private Jdbi jdbi;
    private InformationRegisterPersistence<TestPriceRegister> pricePersistence;
    private InformationRegisterPersistence<TaskStatusHistory> historyPersistence;
    private InformationRegisterPersistence<TestSettingRegister> settingPersistence;
    private UUID productA = UUID.randomUUID();
    private UUID productB = UUID.randomUUID();
    private UUID warehouseA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:inforegtest" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        registry.registerInformationRegister(scanner.scanInformationRegister(TestPriceRegister.class));
        registry.registerInformationRegister(scanner.scanInformationRegister(TestSettingRegister.class));
        registry.registerInformationRegister(scanner.scanInformationRegister(TaskStatusHistory.class));

        new SchemaGenerator(registry).execute(jdbi);

        InformationRegisterDescriptor priceDesc = registry.getInformationRegisterDescriptor(TestPriceRegister.class);
        pricePersistence = new InformationRegisterPersistence<>(jdbi, priceDesc);

        InformationRegisterDescriptor settingDesc = registry.getInformationRegisterDescriptor(TestSettingRegister.class);
        settingPersistence = new InformationRegisterPersistence<>(jdbi, settingDesc);

        InformationRegisterDescriptor historyDesc = registry.getInformationRegisterDescriptor(TaskStatusHistory.class);
        historyPersistence = new InformationRegisterPersistence<>(jdbi, historyDesc);
    }

    @Test
    void write_insertsNewRecord() {
        TestPriceRegister record = new TestPriceRegister();
        record.setPeriod(LocalDateTime.of(2024, 1, 15, 10, 0));
        record.setProduct(productA);
        record.setWarehouse(warehouseA);
        record.setPrice(new BigDecimal("100.50"));

        pricePersistence.write(record);

        List<TestPriceRegister> records = pricePersistence.getRecords(Collections.emptyMap());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getProduct()).isEqualTo(productA);
        assertThat(records.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("100.50"));
    }

    @Test
    void write_upsertsSameDimensionAndPeriod() {
        TestPriceRegister record1 = new TestPriceRegister();
        record1.setPeriod(LocalDateTime.of(2024, 1, 15, 10, 0));
        record1.setProduct(productA);
        record1.setWarehouse(warehouseA);
        record1.setPrice(new BigDecimal("100.00"));

        pricePersistence.write(record1);

        TestPriceRegister record2 = new TestPriceRegister();
        record2.setPeriod(LocalDateTime.of(2024, 1, 15, 14, 0));  // same day, different time
        record2.setProduct(productA);
        record2.setWarehouse(warehouseA);
        record2.setPrice(new BigDecimal("150.00"));

        pricePersistence.write(record2);

        List<TestPriceRegister> records = pricePersistence.getRecords(Collections.emptyMap());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void write_differentPeriod_createsNewRecord() {
        TestPriceRegister jan = new TestPriceRegister();
        jan.setPeriod(LocalDateTime.of(2024, 1, 15, 0, 0));
        jan.setProduct(productA);
        jan.setWarehouse(warehouseA);
        jan.setPrice(new BigDecimal("100.00"));

        TestPriceRegister feb = new TestPriceRegister();
        feb.setPeriod(LocalDateTime.of(2024, 2, 15, 0, 0));
        feb.setProduct(productA);
        feb.setWarehouse(warehouseA);
        feb.setPrice(new BigDecimal("120.00"));

        pricePersistence.write(jan);
        pricePersistence.write(feb);

        List<TestPriceRegister> records = pricePersistence.getRecords(Collections.emptyMap());
        assertThat(records).hasSize(2);
    }

    @Test
    void getSliceLast_returnsLatestRecordPerDimension() {
        writePrice(productA, warehouseA, "2024-01-01", "100.00");
        writePrice(productA, warehouseA, "2024-02-01", "110.00");
        writePrice(productA, warehouseA, "2024-03-01", "120.00");
        writePrice(productB, warehouseA, "2024-01-15", "200.00");
        writePrice(productB, warehouseA, "2024-02-15", "210.00");

        List<TestPriceRegister> slice = pricePersistence.getSliceLast(
                LocalDateTime.of(2024, 2, 20, 0, 0), Collections.emptyMap());

        assertThat(slice).hasSize(2);

        TestPriceRegister priceA = slice.stream()
                .filter(r -> r.getProduct().equals(productA)).findFirst().orElseThrow();
        assertThat(priceA.getPrice()).isEqualByComparingTo("110.00");

        TestPriceRegister priceB = slice.stream()
                .filter(r -> r.getProduct().equals(productB)).findFirst().orElseThrow();
        assertThat(priceB.getPrice()).isEqualByComparingTo("210.00");
    }

    @Test
    void getRecords_withDimensionFilter_returnsOnlyMatching() {
        // Regression for #147: a filtered getRecords used to emit "...infoWHERE ..." (no space
        // before WHERE) and threw a syntax error. Any non-empty filter must produce valid SQL.
        writePrice(productA, warehouseA, "2024-01-01", "100.00");
        writePrice(productB, warehouseA, "2024-01-01", "200.00");

        List<TestPriceRegister> records = pricePersistence.getRecords(
                java.util.Map.of("product", productA));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getProduct()).isEqualTo(productA);
        assertThat(records.get(0).getPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void getSliceLast_withDimensionFilter_returnsLatestForThatDimension() {
        // Regression for #147: a filtered slice-last glued ":filter_xGROUP BY" together
        // (no space after the filter clause) and threw a syntax error.
        writePrice(productA, warehouseA, "2024-01-01", "100.00");
        writePrice(productA, warehouseA, "2024-02-01", "110.00");
        writePrice(productB, warehouseA, "2024-02-01", "210.00");

        List<TestPriceRegister> slice = pricePersistence.getSliceLast(
                LocalDateTime.of(2024, 2, 20, 0, 0), java.util.Map.of("product", productA));

        assertThat(slice).hasSize(1);
        assertThat(slice.get(0).getProduct()).isEqualTo(productA);
        assertThat(slice.get(0).getPrice()).isEqualByComparingTo("110.00");
    }

    @Test
    void getSliceFirst_returnsEarliestRecordPerDimension() {
        writePrice(productA, warehouseA, "2024-01-01", "100.00");
        writePrice(productA, warehouseA, "2024-02-01", "110.00");
        writePrice(productA, warehouseA, "2024-03-01", "120.00");

        List<TestPriceRegister> slice = pricePersistence.getSliceFirst(
                LocalDateTime.of(2024, 1, 15, 0, 0), Collections.emptyMap());

        assertThat(slice).hasSize(1);
        assertThat(slice.get(0).getPrice()).isEqualByComparingTo("110.00");
    }

    @Test
    void nonPeriodicRegister_writesAndReads() {
        TestSettingRegister setting = new TestSettingRegister();
        UUID userId = UUID.randomUUID();
        setting.setUserId(userId);
        setting.setSettingValue("dark_mode");

        settingPersistence.write(setting);

        List<TestSettingRegister> records = settingPersistence.getRecords(Collections.emptyMap());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getSettingValue()).isEqualTo("dark_mode");
    }

    @Test
    void nonPeriodicRegister_upsertsByDimension() {
        UUID userId = UUID.randomUUID();

        TestSettingRegister setting1 = new TestSettingRegister();
        setting1.setUserId(userId);
        setting1.setSettingValue("light_mode");
        settingPersistence.write(setting1);

        TestSettingRegister setting2 = new TestSettingRegister();
        setting2.setUserId(userId);
        setting2.setSettingValue("dark_mode");
        settingPersistence.write(setting2);

        List<TestSettingRegister> records = settingPersistence.getRecords(Collections.emptyMap());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getSettingValue()).isEqualTo("dark_mode");
    }

    @Test
    void typedRefAndEnumDimensions_roundTripAndFilterByDomainValues() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerEnumeration(scanner.scanEnumeration(Channel.class));
        InformationRegisterDescriptor descriptor =
                scanner.scanInformationRegister(TypedInformationRate.class);
        registry.registerInformationRegister(descriptor);
        new SchemaGenerator(registry).execute(jdbi);
        InformationRegisterPersistence<TypedInformationRate> persistence =
                new InformationRegisterPersistence<>(jdbi, descriptor);
        Ref<TestProduct> product = Ref.of(TestProduct.class, UUID.randomUUID());
        TypedInformationRate rate = new TypedInformationRate();
        rate.setProduct(product);
        rate.setChannel(Channel.RETAIL);
        rate.setRate(new BigDecimal("12.50"));

        persistence.write(rate);

        assertThat(persistence.getRecords(java.util.Map.of(
                "product", product,
                "channel", Channel.RETAIL)))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.getProduct()).isEqualTo(product);
                    assertThat(stored.getProduct().type()).isEqualTo(TestProduct.class);
                    assertThat(stored.getChannel()).isEqualTo(Channel.RETAIL);
                    assertThat(stored.getRate()).isEqualByComparingTo("12.50");
                });
    }

    @Test
    void periodTruncation_second() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 3, 15, 14, 30, 45, 123_456_789),
                su.onno.model.Periodicity.SECOND
        )).isEqualTo(LocalDateTime.of(2024, 3, 15, 14, 30, 45));
    }

    @Test
    void periodTruncation_minute() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 3, 15, 14, 30, 45),
                su.onno.model.Periodicity.MINUTE
        )).isEqualTo(LocalDateTime.of(2024, 3, 15, 14, 30));
    }

    @Test
    void periodTruncation_hour() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 3, 15, 14, 30, 45),
                su.onno.model.Periodicity.HOUR
        )).isEqualTo(LocalDateTime.of(2024, 3, 15, 14, 0));
    }

    /**
     * The repro from issue #336: two intraday facts for the same dimension tuple. Under the old
     * DAY floor both writes collapsed onto midnight and the second silently overwrote the first;
     * a SECOND-periodicity register keeps them as distinct rows.
     */
    @Test
    void write_subDayPeriodicity_keepsBothSameDayFacts() {
        UUID task = UUID.randomUUID();

        TaskStatusHistory morning = new TaskStatusHistory();
        morning.setPeriod(LocalDateTime.of(2026, 8, 19, 9, 15, 0));
        morning.setTask(task);
        morning.setStatus("VERIFICATION");
        historyPersistence.write(morning);

        TaskStatusHistory afternoon = new TaskStatusHistory();
        afternoon.setPeriod(LocalDateTime.of(2026, 8, 19, 14, 40, 0));
        afternoon.setTask(task);
        afternoon.setStatus("IN_PROGRESS");
        historyPersistence.write(afternoon);

        assertThat(historyPersistence.getRecords(Collections.emptyMap()))
                .extracting(TaskStatusHistory::getStatus)
                .containsExactlyInAnyOrder("VERIFICATION", "IN_PROGRESS");
    }

    /** Same second + same dimensions is still one key, so the upsert semantics are unchanged. */
    @Test
    void write_subDayPeriodicity_upsertsWithinTheSameSecond() {
        UUID task = UUID.randomUUID();

        TaskStatusHistory first = new TaskStatusHistory();
        first.setPeriod(LocalDateTime.of(2026, 8, 19, 9, 15, 0, 100));
        first.setTask(task);
        first.setStatus("VERIFICATION");
        historyPersistence.write(first);

        TaskStatusHistory second = new TaskStatusHistory();
        second.setPeriod(LocalDateTime.of(2026, 8, 19, 9, 15, 0, 900));
        second.setTask(task);
        second.setStatus("IN_PROGRESS");
        historyPersistence.write(second);

        assertThat(historyPersistence.getRecords(Collections.emptyMap()))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo("IN_PROGRESS"));
    }

    /** A sub-day register still answers "what was true at 10:00?" through the slice reads. */
    @Test
    void getSliceLast_subDayPeriodicity_picksTheLatestFactBeforeTheCutoff() {
        UUID task = UUID.randomUUID();

        TaskStatusHistory morning = new TaskStatusHistory();
        morning.setPeriod(LocalDateTime.of(2026, 8, 19, 9, 15, 0));
        morning.setTask(task);
        morning.setStatus("VERIFICATION");
        historyPersistence.write(morning);

        TaskStatusHistory afternoon = new TaskStatusHistory();
        afternoon.setPeriod(LocalDateTime.of(2026, 8, 19, 14, 40, 0));
        afternoon.setTask(task);
        afternoon.setStatus("IN_PROGRESS");
        historyPersistence.write(afternoon);

        assertThat(historyPersistence.getSliceLast(
                LocalDateTime.of(2026, 8, 19, 10, 0), Collections.emptyMap()))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo("VERIFICATION"));
    }

    @Test
    void periodTruncation_day() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 3, 15, 14, 30, 45),
                su.onno.model.Periodicity.DAY
        )).isEqualTo(LocalDateTime.of(2024, 3, 15, 0, 0));
    }

    @Test
    void periodTruncation_month() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 3, 15, 14, 30),
                su.onno.model.Periodicity.MONTH
        )).isEqualTo(LocalDateTime.of(2024, 3, 1, 0, 0));
    }

    @Test
    void periodTruncation_quarter() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 5, 15, 14, 30),
                su.onno.model.Periodicity.QUARTER
        )).isEqualTo(LocalDateTime.of(2024, 4, 1, 0, 0));
    }

    @Test
    void periodTruncation_year() {
        assertThat(InformationRegisterPersistence.truncatePeriod(
                LocalDateTime.of(2024, 7, 15, 14, 30),
                su.onno.model.Periodicity.YEAR
        )).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
    }

    private void writePrice(UUID product, UUID warehouse, String date, String price) {
        TestPriceRegister record = new TestPriceRegister();
        record.setPeriod(LocalDateTime.parse(date + "T00:00:00"));
        record.setProduct(product);
        record.setWarehouse(warehouse);
        record.setPrice(new BigDecimal(price));
        pricePersistence.write(record);
    }
}
