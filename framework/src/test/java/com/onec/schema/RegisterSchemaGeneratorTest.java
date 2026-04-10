package com.onec.schema;

import com.onec.fixtures.TestSalesRegister;
import com.onec.fixtures.TestStockRegister;
import com.onec.metadata.*;

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

        SchemaGenerator generator = new SchemaGenerator(registry, new DefaultTypeMapping());
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(2);

        String movementDDL = ddl.get(0);
        assertThat(movementDDL).contains("CREATE TABLE IF NOT EXISTS _register_TestStock");
        assertThat(movementDDL).contains("_id UUID PRIMARY KEY");
        assertThat(movementDDL).contains("_period TIMESTAMP");
        assertThat(movementDDL).contains("_active BOOLEAN DEFAULT TRUE");
        assertThat(movementDDL).contains("_document_ref UUID");
        assertThat(movementDDL).contains("_movement_type VARCHAR(10)");
        assertThat(movementDDL).contains("product UUID");
        assertThat(movementDDL).contains("warehouse UUID");
        assertThat(movementDDL).contains("quantity DECIMAL(15,2)");

        String totalsDDL = ddl.get(1);
        assertThat(totalsDDL).contains("CREATE TABLE IF NOT EXISTS _register_TestStock_totals");
        assertThat(totalsDDL).contains("PRIMARY KEY (product, warehouse)");
        assertThat(totalsDDL).contains("quantity DECIMAL(15,2) DEFAULT 0");
    }

    @Test
    void generateDDL_turnoverRegister_createsMovementTableOnly() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(TestSalesRegister.class));

        SchemaGenerator generator = new SchemaGenerator(registry, new DefaultTypeMapping());
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(1);
        assertThat(ddl.get(0)).contains("_register_TestSales");
        assertThat(ddl.get(0)).doesNotContain("_totals");
    }

    @Test
    void execute_createsTablesInH2() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:regschematest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        SchemaGenerator generator = new SchemaGenerator(registry, new DefaultTypeMapping());
        generator.execute(jdbi);

        List<String> tables = jdbi.withHandle(handle ->
                handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_NAME LIKE '_REGISTER_TESTSTOCK%' ORDER BY TABLE_NAME"
                ).mapTo(String.class).list()
        );

        assertThat(tables).containsExactly("_REGISTER_TESTSTOCK", "_REGISTER_TESTSTOCK_TOTALS");
    }
}
