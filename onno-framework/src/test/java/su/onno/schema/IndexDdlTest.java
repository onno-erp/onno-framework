package su.onno.schema;

import su.onno.fixtures.TestCustomer;
import su.onno.fixtures.TestInvoice;
import su.onno.fixtures.TestRegion;
import su.onno.fixtures.TestStockRegister;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;

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
                "CREATE INDEX IF NOT EXISTS idx_onno_outbox__status ON onno_outbox (_status)",
                // ref attribute on a catalog
                "CREATE INDEX IF NOT EXISTS idx_catalog_test_customers_region"
                        + " ON catalog_test_customers (region)",
                // keyset pagination seeks by (sortKey, _id) on catalogs
                "CREATE INDEX IF NOT EXISTS idx_catalog_test_customers__code__id"
                        + " ON catalog_test_customers (_code, _id)",
                "CREATE INDEX IF NOT EXISTS idx_catalog_test_customers__description__id"
                        + " ON catalog_test_customers (_description, _id)",
                // document lists order newest-first; keyset seeks on (_date, _id)
                "CREATE INDEX IF NOT EXISTS idx_document_test_invoices__date__id"
                        + " ON document_test_invoices (_date, _id)",
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

    @Test
    void trigramSearchIndexesAreEmittedOnPostgresOnly() {
        SchemaGenerator generator = new SchemaGenerator(registry());

        // H2 has no pg_trgm — no statements, so search falls back to LIKE scans.
        assertThat(generator.generateSearchIndexDDL(SqlDialect.H2)).isEmpty();

        List<String> pg = generator.generateSearchIndexDDL(SqlDialect.POSTGRESQL);
        assertThat(pg).first().isEqualTo("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        // The indexed expression must match the query services' search clause byte-for-byte.
        assertThat(pg).contains(
                "CREATE INDEX IF NOT EXISTS idx_trgm_catalog_test_customers__description"
                        + " ON catalog_test_customers USING gin"
                        + " (LOWER(CAST(_description AS VARCHAR)) gin_trgm_ops)",
                "CREATE INDEX IF NOT EXISTS idx_trgm_document_test_invoices__number"
                        + " ON document_test_invoices USING gin"
                        + " (LOWER(CAST(_number AS VARCHAR)) gin_trgm_ops)");
    }
}
