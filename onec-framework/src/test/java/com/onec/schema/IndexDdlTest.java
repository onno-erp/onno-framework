package com.onec.schema;

import com.onec.fixtures.TestCustomer;
import com.onec.fixtures.TestInvoice;
import com.onec.fixtures.TestRegion;
import com.onec.fixtures.TestStockRegister;
import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IndexDdlTest {

    private MetadataRegistry registry() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestCustomer.class));
        registry.registerCatalog(scanner.scan(TestRegion.class));
        registry.registerDocument(scanner.scanDocument(TestInvoice.class));
        registry.registerAccumulation(scanner.scanRegister(TestStockRegister.class));
        return registry;
    }

    @Test
    void emitsIndexesForFrameworkQueriedColumns() {
        List<String> ddl = new SchemaGenerator(registry()).generateIndexDDL();

        assertThat(ddl).contains(
                // outbox relay polls by status
                "CREATE INDEX IF NOT EXISTS idx_onec_outbox__status ON onec_outbox (_status)",
                // ref attribute on a catalog
                "CREATE INDEX IF NOT EXISTS idx_catalog_test_customers_region"
                        + " ON catalog_test_customers (region)",
                // document lists order by date
                "CREATE INDEX IF NOT EXISTS idx_document_test_invoices__date"
                        + " ON document_test_invoices (_date)",
                // tabular-section rows are loaded by owning document
                "CREATE INDEX IF NOT EXISTS idx_document_test_invoices_items__parent_id"
                        + " ON document_test_invoices_items (_parent_id)",
                // register movements are filtered by period, unposted by document ref,
                // and aggregated by dimensions
                "CREATE INDEX IF NOT EXISTS idx_register_test_stock__period"
                        + " ON register_test_stock (_period)",
                "CREATE INDEX IF NOT EXISTS idx_register_test_stock__document_ref"
                        + " ON register_test_stock (_document_ref)",
                "CREATE INDEX IF NOT EXISTS idx_register_test_stock_product"
                        + " ON register_test_stock (product)",
                "CREATE INDEX IF NOT EXISTS idx_register_test_stock_warehouse"
                        + " ON register_test_stock (warehouse)");
    }

    @Test
    void executeCreatesIndexesOnH2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        new SchemaGenerator(registry()).execute(jdbi);

        List<String> indexes = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                                + "WHERE TABLE_NAME = 'REGISTER_TEST_STOCK'")
                .mapTo(String.class)
                .list());

        assertThat(indexes)
                .contains("IDX_REGISTER_TEST_STOCK__PERIOD",
                        "IDX_REGISTER_TEST_STOCK__DOCUMENT_REF",
                        "IDX_REGISTER_TEST_STOCK_PRODUCT",
                        "IDX_REGISTER_TEST_STOCK_WAREHOUSE");

        // Re-running must be a no-op thanks to IF NOT EXISTS.
        new SchemaGenerator(registry()).execute(jdbi);
    }

    @Test
    void longIndexNamesAreCappedForPostgresIdentifierLimit() {
        String name = SchemaGenerator.indexName("a".repeat(80), "some_ref_column");

        assertThat(name.length()).isLessThanOrEqualTo(63);
        assertThat(name).startsWith("idx_");
        // Distinct long names must not collide after capping.
        String other = SchemaGenerator.indexName("a".repeat(80), "other_ref_column");
        assertThat(name).isNotEqualTo(other);
    }
}
