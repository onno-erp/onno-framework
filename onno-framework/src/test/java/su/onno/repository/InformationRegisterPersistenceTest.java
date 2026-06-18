package su.onno.repository;

import su.onno.fixtures.TestPriceRegister;
import su.onno.fixtures.TestSettingRegister;
import su.onno.metadata.*;
import su.onno.schema.SchemaGenerator;

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

    private Jdbi jdbi;
    private InformationRegisterPersistence<TestPriceRegister> pricePersistence;
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

        new SchemaGenerator(registry).execute(jdbi);

        InformationRegisterDescriptor priceDesc = registry.getInformationRegisterDescriptor(TestPriceRegister.class);
        pricePersistence = new InformationRegisterPersistence<>(jdbi, priceDesc);

        InformationRegisterDescriptor settingDesc = registry.getInformationRegisterDescriptor(TestSettingRegister.class);
        settingPersistence = new InformationRegisterPersistence<>(jdbi, settingDesc);
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
