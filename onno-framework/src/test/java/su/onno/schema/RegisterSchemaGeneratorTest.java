package su.onno.schema;

import su.onno.fixtures.TestSalesRegister;
import su.onno.fixtures.TestStockRegister;
import su.onno.metadata.*;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RegisterSchemaGeneratorTest {

    @Test
    void generateDDL_balanceRegister_createsMovementAndTotalsTable() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(4);

        String movementDDL = ddl.get(2);
        assertThat(movementDDL).contains("CREATE TABLE IF NOT EXISTS register_test_stock");
        assertThat(movementDDL).contains("_id UUID PRIMARY KEY");
        assertThat(movementDDL).contains("_period TIMESTAMP");
        assertThat(movementDDL).contains("_active BOOLEAN DEFAULT TRUE");
        assertThat(movementDDL).contains("_document_ref UUID");
        assertThat(movementDDL).contains("_movement_type VARCHAR(10)");
        assertThat(movementDDL).contains("product UUID");
        assertThat(movementDDL).contains("warehouse UUID");
        assertThat(movementDDL).contains("quantity DECIMAL(15,2)");

        String totalsDDL = ddl.get(3);
        assertThat(totalsDDL).contains("CREATE TABLE IF NOT EXISTS register_test_stock_totals");
        assertThat(totalsDDL).contains("PRIMARY KEY (product, warehouse)");
        assertThat(totalsDDL).contains("quantity DECIMAL(15,2) DEFAULT 0");
    }

    @Test
    void generateDDL_turnoverRegister_createsMovementTableOnly() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(TestSalesRegister.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(3);
        assertThat(ddl.get(2)).contains("register_test_sales");
        assertThat(ddl.get(2)).doesNotContain("_totals");
    }

    @Test
    void execute_createsTablesInH2() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:regschematest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        SchemaGenerator generator = new SchemaGenerator(registry);
        generator.execute(jdbi);

        List<String> tables = jdbi.withHandle(handle ->
                handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE UPPER(TABLE_NAME) LIKE 'REGISTER_TEST_STOCK%' ORDER BY TABLE_NAME"
                ).mapTo(String.class).list()
        );

        assertThat(tables).containsExactly("REGISTER_TEST_STOCK", "REGISTER_TEST_STOCK_TOTALS");
    }
}
