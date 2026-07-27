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

    @Test
    void execute_backfillsExecutionTokenForLegacyActiveProcess() {
        MetadataRegistry registry = new MetadataRegistry();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:process-token-backfill;DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        jdbi.useHandle(handle -> {
            handle.execute(DdlRenderer.createTable(SchemaModelBuilder.processInstancesTable()));
            handle.execute("""
                    create table onno_process_work_items (
                        _id UUID primary key,
                        _instance_id UUID references onno_process_instances(_id) not null,
                        _step_key VARCHAR(255) not null,
                        _title VARCHAR(255) not null,
                        _status VARCHAR(32) not null,
                        _created_at TIMESTAMP not null,
                        _version INTEGER default 0 not null
                    )
                    """);
            java.util.UUID instanceId = java.util.UUID.randomUUID();
            java.util.UUID workItemId = java.util.UUID.randomUUID();
            handle.createUpdate("""
                    insert into onno_process_instances
                        (_id, _definition_key, _definition_version, _payload, _current_step,
                         _status, _started_at, _updated_at, _version)
                    values (:id, 'legacy', 1, '{}', 'review', 'ACTIVE',
                            current_timestamp, current_timestamp, 0)
                    """).bind("id", instanceId).execute();
            handle.createUpdate("""
                    insert into onno_process_work_items
                        (_id, _instance_id, _step_key, _title, _status,
                         _created_at, _version)
                    values (:id, :instance, 'review', 'Review', 'OPEN',
                            current_timestamp, 0)
                    """).bind("id", workItemId).bind("instance", instanceId).execute();
        });

        new SchemaGenerator(registry).execute(jdbi);

        int tokenCount = jdbi.withHandle(handle -> handle.createQuery("""
                        select count(*) from onno_process_tokens
                         where _status = 'WAITING_HUMAN' and _step_key = 'review'
                        """).mapTo(Integer.class).one());
        int linkedWorkItemCount = jdbi.withHandle(handle -> handle.createQuery("""
                        select count(*) from onno_process_work_items where _token_id is not null
                        """).mapTo(Integer.class).one());
        assertThat(tokenCount).isEqualTo(1);
        assertThat(linkedWorkItemCount).isEqualTo(1);
    }
}
