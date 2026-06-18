package su.onno.schema;

import su.onno.fixtures.TestProduct;
import su.onno.metadata.*;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SchemaGeneratorTest {

    @Test
    void generateDDL_singleCatalog_correctCreateTable() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestProduct.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(3);
        assertThat(ddl.get(1)).contains("CREATE TABLE IF NOT EXISTS onno_outbox");

        String sql = ddl.get(2);
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS catalog_test_products");
        assertThat(sql).contains("_id UUID PRIMARY KEY");
        assertThat(sql).contains("_code VARCHAR(9)");
        assertThat(sql).contains("_description VARCHAR(255)");
        assertThat(sql).contains("_deletion_mark BOOLEAN DEFAULT FALSE");
        assertThat(sql).contains("_is_folder BOOLEAN DEFAULT FALSE");
        assertThat(sql).contains("_parent UUID");
        assertThat(sql).contains("_version INTEGER DEFAULT 0");
        assertThat(sql).contains("full_name VARCHAR(100)");
        assertThat(sql).contains("unit_price DECIMAL(15,2)");
        assertThat(sql).contains("unit VARCHAR(25)");
    }

    @Test
    void execute_createsTableInH2() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestProduct.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:schematest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        SchemaGenerator generator = new SchemaGenerator(registry);
        generator.execute(jdbi);

        List<String> tables = jdbi.withHandle(handle ->
                handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'CATALOG_TEST_PRODUCTS'"
                ).mapTo(String.class).list()
        );

        assertThat(tables).hasSize(1);
    }

    @Test
    void execute_addsMissingGeneratedColumnsToExistingCatalogTable() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestProduct.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:migrationtest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        jdbi.useHandle(handle -> handle.execute("""
                CREATE TABLE catalog_test_products (
                    _id UUID PRIMARY KEY,
                    _code VARCHAR(9),
                    _description VARCHAR(255),
                    _deletion_mark BOOLEAN DEFAULT FALSE
                )
                """));

        new SchemaGenerator(registry).execute(jdbi);

        List<String> columns = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE TABLE_NAME = 'CATALOG_TEST_PRODUCTS'")
                .mapTo(String.class)
                .list());

        assertThat(columns).contains("_IS_FOLDER", "_PARENT", "_VERSION", "FULL_NAME", "UNIT_PRICE", "UNIT");
    }
}
