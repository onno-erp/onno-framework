package com.onec.schema;

import com.onec.fixtures.TestProduct;
import com.onec.metadata.*;

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
        registry.register(scanner.scan(TestProduct.class));

        SchemaGenerator generator = new SchemaGenerator(registry, new DefaultTypeMapping());
        List<String> ddl = generator.generateDDL();

        assertThat(ddl).hasSize(1);

        String sql = ddl.get(0);
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS _catalog_TestProducts");
        assertThat(sql).contains("_id UUID PRIMARY KEY");
        assertThat(sql).contains("_code VARCHAR(9)");
        assertThat(sql).contains("_description VARCHAR(255)");
        assertThat(sql).contains("_deletion_mark BOOLEAN DEFAULT FALSE");
        assertThat(sql).contains("full_name VARCHAR(100)");
        assertThat(sql).contains("unit_price DECIMAL(15,2)");
        assertThat(sql).contains("unit VARCHAR(25)");
    }

    @Test
    void execute_createsTableInH2() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.register(scanner.scan(TestProduct.class));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:schematest;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        SchemaGenerator generator = new SchemaGenerator(registry, new DefaultTypeMapping());
        generator.execute(jdbi);

        List<String> tables = jdbi.withHandle(handle ->
                handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '_CATALOG_TESTPRODUCTS'"
                ).mapTo(String.class).list()
        );

        assertThat(tables).hasSize(1);
    }
}
