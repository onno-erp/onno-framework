package su.onno.schema;

import su.onno.fixtures.TestCompanyName;
import su.onno.fixtures.TestOrderStatus;
import su.onno.fixtures.TestPriceRegister;
import su.onno.fixtures.TestSettingRegister;
import su.onno.metadata.*;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class Plan4SchemaGeneratorTest {

    private MetadataRegistry registry;
    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        registry = new MetadataRegistry();
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void generateDDL_enumeration_createsEnumTable() {
        registry.registerEnumeration(scanner.scanEnumeration(TestOrderStatus.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        String enumDDL = ddl.stream()
                .filter(s -> s.contains("enum_order_statuses"))
                .findFirst().orElseThrow();

        assertThat(enumDDL).contains("_id UUID PRIMARY KEY");
        assertThat(enumDDL).contains("_name VARCHAR(255)");
        assertThat(enumDDL).contains("_order INTEGER");
    }

    @Test
    void generateDDL_enumeration_createsInsertStatements() {
        registry.registerEnumeration(scanner.scanEnumeration(TestOrderStatus.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        long mergeCount = ddl.stream().filter(s -> s.contains("MERGE INTO enum_order_statuses")).count();
        assertThat(mergeCount).isEqualTo(3);
    }

    @Test
    void generateDDL_periodicInfoRegister_includesPeriodAndUniqueConstraint() {
        registry.registerInformationRegister(scanner.scanInformationRegister(TestPriceRegister.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        String regDDL = ddl.stream()
                .filter(s -> s.contains("inforeg_prices"))
                .findFirst().orElseThrow();

        assertThat(regDDL).contains("_period TIMESTAMP");
        assertThat(regDDL).contains("product UUID");
        assertThat(regDDL).contains("warehouse UUID");
        assertThat(regDDL).contains("price DECIMAL(15,2)");
        assertThat(regDDL).contains("UNIQUE (_period, product, warehouse)");
    }

    @Test
    void generateDDL_nonPeriodicInfoRegister_omitsPeriod() {
        registry.registerInformationRegister(scanner.scanInformationRegister(TestSettingRegister.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        String regDDL = ddl.stream()
                .filter(s -> s.contains("inforeg_settings"))
                .findFirst().orElseThrow();

        assertThat(regDDL).doesNotContain("_period");
        assertThat(regDDL).contains("UNIQUE (user_id)");
    }

    @Test
    void generateDDL_constants_createsConstantsTable() {
        registry.registerConstant(scanner.scanConstant(TestCompanyName.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        String constDDL = ddl.stream()
                .filter(s -> s.contains("constants") && s.contains("_name"))
                .findFirst().orElseThrow();

        assertThat(constDDL).contains("_name VARCHAR(255) PRIMARY KEY");
        assertThat(constDDL).contains("_value TEXT");
    }

    @Test
    void execute_allPlan4Types_createsTablesInH2() {
        registry.registerEnumeration(scanner.scanEnumeration(TestOrderStatus.class));
        registry.registerInformationRegister(scanner.scanInformationRegister(TestPriceRegister.class));
        registry.registerConstant(scanner.scanConstant(TestCompanyName.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:plan4schematest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        SchemaGenerator generator = new SchemaGenerator(registry);
        generator.execute(jdbi);

        List<String> tables = jdbi.withHandle(handle ->
                handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME"
                ).mapTo(String.class).list()
        );

        assertThat(tables).contains(
                "CONSTANTS",
                "ENUM_ORDER_STATUSES",
                "INFOREG_PRICES",
                "ONNO_SEQUENCES"
        );
    }
}
