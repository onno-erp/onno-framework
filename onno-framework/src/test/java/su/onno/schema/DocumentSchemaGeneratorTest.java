package su.onno.schema;

import su.onno.fixtures.TestInvoice;
import su.onno.metadata.*;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DocumentSchemaGeneratorTest {

    @Test
    void generateDDL_singleDocument_correctCreateTable() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(TestInvoice.class));

        SchemaGenerator generator = new SchemaGenerator(registry);
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(4);

        String docDDL = ddl.get(2);
        assertThat(docDDL).contains("CREATE TABLE IF NOT EXISTS document_test_invoices");
        assertThat(docDDL).contains("_id UUID PRIMARY KEY");
        assertThat(docDDL).contains("_number VARCHAR(11)");
        assertThat(docDDL).contains("_date TIMESTAMP");
        assertThat(docDDL).contains("_posted BOOLEAN DEFAULT FALSE");
        assertThat(docDDL).contains("_deletion_mark BOOLEAN DEFAULT FALSE");
        assertThat(docDDL).contains("_version INTEGER DEFAULT 0");
        assertThat(docDDL).contains("counterparty VARCHAR(200)");

        String tsDDL = ddl.get(3);
        assertThat(tsDDL).contains("CREATE TABLE IF NOT EXISTS document_test_invoices_items");
        assertThat(tsDDL).contains("_parent_id UUID REFERENCES document_test_invoices(_id)");
        assertThat(tsDDL).contains("_line_number INTEGER");
        assertThat(tsDDL).contains("product_name VARCHAR(100)");
        assertThat(tsDDL).contains("quantity DECIMAL(15,2)");
        assertThat(tsDDL).contains("price DECIMAL(15,2)");
    }

    @Test
    void execute_createsTablesInH2() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(TestInvoice.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:docschematest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        SchemaGenerator generator = new SchemaGenerator(registry);
        generator.execute(jdbi);

        List<String> tables = jdbi.withHandle(handle ->
                handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE UPPER(TABLE_NAME) LIKE 'DOCUMENT_TEST_INVOICES%' ORDER BY TABLE_NAME"
                ).mapTo(String.class).list()
        );

        assertThat(tables).containsExactly("DOCUMENT_TEST_INVOICES", "DOCUMENT_TEST_INVOICES_ITEMS");
    }
}
