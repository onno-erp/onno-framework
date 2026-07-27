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

        assertThat(ddl).hasSize(8);
        assertThat(ddl.get(1)).contains("CREATE TABLE IF NOT EXISTS onno_outbox");
        assertThat(ddl.get(3)).contains("CREATE TABLE IF NOT EXISTS onno_process_tokens");
        assertThat(ddl.get(4))
                .contains("_assignee_display VARCHAR(255)")
                .contains("_subject_kind VARCHAR(32)")
                .contains("_subject_entity VARCHAR(255)")
                .contains("_subject_id UUID")
                .contains("_subject_label VARCHAR(255)");
        assertThat(ddl.get(5)).contains("CREATE TABLE IF NOT EXISTS onno_process_work_item_events");

        String sql = ddl.get(7);
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

}
