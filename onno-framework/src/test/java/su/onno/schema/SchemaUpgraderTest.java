package su.onno.schema;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.fixtures.TestSecretAccount;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class SchemaUpgraderTest {

    // ----- metadata "versions" of the same catalog -----

    @Catalog(name = "MigProducts")
    static class ProductV1 extends CatalogObject {
        @Attribute(length = 100)
        private String fullName;
    }

    @Catalog(name = "MigProducts")
    static class ProductWithPhone extends CatalogObject {
        @Attribute(length = 100)
        private String fullName;
        @Attribute(length = 50)
        private String phone;
    }

    @Catalog(name = "MigProducts")
    static class ProductRenamedColumn extends CatalogObject {
        @Attribute(length = 100, previousNames = "fullName")
        private String fullTitle;
    }

    @Catalog(name = "MigCatalogue", previousNames = "MigProducts")
    static class ProductRenamedTable extends CatalogObject {
        @Attribute(length = 100)
        private String fullName;
    }

    @Catalog(name = "MigProducts")
    static class ProductWidened extends CatalogObject {
        @Attribute(length = 200)
        private String fullName;
    }

    @Catalog(name = "MigProducts")
    static class ProductNarrowed extends CatalogObject {
        @Attribute(length = 20)
        private String fullName;
    }

    @Catalog(name = "MigProducts")
    static class ProductRequired extends CatalogObject {
        @Attribute(length = 100)
        private String fullName;
        @Attribute(length = 20, required = true)
        private String status;
    }

    @Catalog(name = "MigProducts")
    static class ProductOptionalStatus extends CatalogObject {
        @Attribute(length = 100)
        private String fullName;
        @Attribute(length = 20)
        private String status;
    }

    private static final String TABLE = "catalog_mig_products";

    private Jdbi h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }

    private MetadataRegistry registryOf(Class<?>... catalogClasses) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        for (Class<?> clazz : catalogClasses) {
            registry.registerCatalog(scanner.scan(clazz));
        }
        return registry;
    }

    private MigrationPlan apply(Jdbi jdbi, Class<?>... catalogClasses) {
        return new SchemaUpgrader(registryOf(catalogClasses), SchemaMode.APPLY, false).run(jdbi);
    }

    private void insertProduct(Jdbi jdbi, String fullName) {
        jdbi.useHandle(handle -> handle.execute(
                "INSERT INTO " + TABLE + " (_id, full_name) VALUES (?, ?)",
                UUID.randomUUID(), fullName));
    }

    private List<String> columns(Jdbi jdbi, String table) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = :t")
                .bind("t", table.toUpperCase())
                .mapTo(String.class)
                .list());
    }

    private int varcharLength(Jdbi jdbi, String table, String column) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE TABLE_NAME = :t AND COLUMN_NAME = :c")
                .bind("t", table.toUpperCase())
                .bind("c", column.toUpperCase())
                .mapTo(Integer.class)
                .one());
    }

    @Test
    void freshDatabase_createsTablesAndRecordsBaseline() {
        Jdbi jdbi = h2("upg_fresh");
        apply(jdbi, ProductV1.class);

        assertThat(columns(jdbi, TABLE)).contains("_ID", "_CODE", "FULL_NAME");
        int historyRows = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM onno_schema_history WHERE _kind = 'SCHEMA'").mapTo(Integer.class).one());
        assertThat(historyRows).isEqualTo(1);

        // A second identical boot is a no-op: no new plan entries, no new history rows.
        MigrationPlan second = apply(jdbi, ProductV1.class);
        assertThat(second.isEmpty()).isTrue();
        int afterSecond = jdbi.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM onno_schema_history WHERE _kind = 'SCHEMA'").mapTo(Integer.class).one());
        assertThat(afterSecond).isEqualTo(1);
    }

    @Test
    void addedAttribute_addsColumnKeepingRows() {
        Jdbi jdbi = h2("upg_add");
        apply(jdbi, ProductV1.class);
        insertProduct(jdbi, "Widget");

        MigrationPlan plan = apply(jdbi, ProductWithPhone.class);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.ADD_COLUMN
                && c.column().equals("phone"));
        assertThat(columns(jdbi, TABLE)).contains("PHONE");
        String name = jdbi.withHandle(h -> h.createQuery("SELECT full_name FROM " + TABLE)
                .mapTo(String.class).one());
        assertThat(name).isEqualTo("Widget");
    }

    @Test
    void secretAttribute_addedToExistingTable_usesUnboundedText() {
        Jdbi jdbi = h2("upg_secret_add");
        jdbi.useHandle(handle -> handle.execute("""
                CREATE TABLE catalog_test_secret_accounts (
                    _id UUID PRIMARY KEY,
                    _code VARCHAR(9),
                    _description VARCHAR(255),
                    _deletion_mark BOOLEAN DEFAULT FALSE
                )
                """));

        apply(jdbi, TestSecretAccount.class);

        assertThat(columns(jdbi, "catalog_test_secret_accounts"))
                .contains("USERNAME", "WS_PASSWORD");
        assertThat(varcharLength(jdbi, "catalog_test_secret_accounts", "ws_password"))
                .isGreaterThan(65_535);
    }

    @Test
    void renamedAttribute_renamesColumnKeepingData() {
        Jdbi jdbi = h2("upg_rename_col");
        apply(jdbi, ProductV1.class);
        insertProduct(jdbi, "Widget");

        MigrationPlan plan = apply(jdbi, ProductRenamedColumn.class);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.RENAME_COLUMN);
        assertThat(columns(jdbi, TABLE)).contains("FULL_TITLE").doesNotContain("FULL_NAME");
        String title = jdbi.withHandle(h -> h.createQuery("SELECT full_title FROM " + TABLE)
                .mapTo(String.class).one());
        assertThat(title).isEqualTo("Widget");
    }

    @Test
    void renamedCatalog_renamesTableKeepingData() {
        Jdbi jdbi = h2("upg_rename_table");
        apply(jdbi, ProductV1.class);
        insertProduct(jdbi, "Widget");

        MigrationPlan plan = new SchemaUpgrader(registryOf(ProductRenamedTable.class),
                SchemaMode.APPLY, false).run(jdbi);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.RENAME_TABLE);
        String name = jdbi.withHandle(h -> h.createQuery("SELECT full_name FROM catalog_mig_catalogue")
                .mapTo(String.class).one());
        assertThat(name).isEqualTo("Widget");
        assertThat(columns(jdbi, TABLE)).isEmpty();
    }

    @Test
    void widenedType_altersColumnKeepingData() {
        Jdbi jdbi = h2("upg_widen");
        apply(jdbi, ProductV1.class);
        insertProduct(jdbi, "Widget");

        MigrationPlan plan = apply(jdbi, ProductWidened.class);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.ALTER_COLUMN_TYPE
                && !c.destructive());
        assertThat(varcharLength(jdbi, TABLE, "full_name")).isEqualTo(200);
        String name = jdbi.withHandle(h -> h.createQuery("SELECT full_name FROM " + TABLE)
                .mapTo(String.class).one());
        assertThat(name).isEqualTo("Widget");
    }

    @Test
    void narrowedType_isDestructiveAndGated() {
        Jdbi jdbi = h2("upg_narrow");
        apply(jdbi, ProductV1.class);
        insertProduct(jdbi, "Widget");

        MigrationPlan plan = apply(jdbi, ProductNarrowed.class);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.ALTER_COLUMN_TYPE
                && c.destructive());
        assertThat(varcharLength(jdbi, TABLE, "full_name")).isEqualTo(100);

        new SchemaUpgrader(registryOf(ProductNarrowed.class), SchemaMode.APPLY, true).run(jdbi);
        assertThat(varcharLength(jdbi, TABLE, "full_name")).isEqualTo(20);
    }

    @Test
    void droppedAttribute_isDestructiveAndGated() {
        Jdbi jdbi = h2("upg_drop");
        apply(jdbi, ProductWithPhone.class);

        MigrationPlan plan = apply(jdbi, ProductV1.class);

        assertThat(plan.changes()).anyMatch(c -> c.type() == SchemaChange.Type.DROP_COLUMN
                && c.column().equals("phone") && c.destructive());
        assertThat(columns(jdbi, TABLE)).contains("PHONE");

        new SchemaUpgrader(registryOf(ProductV1.class), SchemaMode.APPLY, true).run(jdbi);
        assertThat(columns(jdbi, TABLE)).doesNotContain("PHONE");
    }

    @Test
    void requiredAttribute_addedToPopulatedTable_backfillsAndEnforcesNotNull() {
        Jdbi jdbi = h2("upg_required");
        apply(jdbi, ProductV1.class);
        insertProduct(jdbi, "Widget");

        apply(jdbi, ProductRequired.class);

        String nullable = jdbi.withHandle(h -> h.createQuery(
                        "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE TABLE_NAME = :t AND COLUMN_NAME = 'STATUS'")
                .bind("t", TABLE.toUpperCase())
                .mapTo(String.class)
                .one());
        assertThat(nullable).isEqualTo("NO");
        String status = jdbi.withHandle(h -> h.createQuery("SELECT status FROM " + TABLE)
                .mapTo(String.class).one());
        assertThat(status).isEqualTo("");
    }

    @Test
    void existingAttributeCanLoosenAndTightenNullabilitySafely() {
        Jdbi jdbi = h2("upg_nullability");
        apply(jdbi, ProductRequired.class);

        MigrationPlan loosen = apply(jdbi, ProductOptionalStatus.class);
        assertThat(loosen.changes()).anyMatch(change ->
                change.type() == SchemaChange.Type.ALTER_COLUMN_NULLABILITY
                        && !change.destructive());
        assertThat(isNullable(jdbi, "status")).isEqualTo("YES");

        insertProduct(jdbi, "Nullable widget");
        MigrationPlan tighten = apply(jdbi, ProductRequired.class);
        assertThat(tighten.changes()).anyMatch(change ->
                change.type() == SchemaChange.Type.ALTER_COLUMN_NULLABILITY
                        && change.destructive());
        assertThat(isNullable(jdbi, "status")).isEqualTo("YES");

        jdbi.useHandle(handle -> handle.execute(
                "UPDATE " + TABLE + " SET status = '' WHERE status IS NULL"));
        new SchemaUpgrader(registryOf(ProductRequired.class), SchemaMode.APPLY, true).run(jdbi);
        assertThat(isNullable(jdbi, "status")).isEqualTo("NO");
    }

    @Test
    void unmatchedExternalColumnIsNotProposedForDeletion() {
        Jdbi jdbi = h2("upg_external_column");
        apply(jdbi, ProductV1.class);
        jdbi.useHandle(handle -> handle.execute(
                "ALTER TABLE " + TABLE + " ADD COLUMN external_note VARCHAR(255)"));

        MigrationPlan plan = apply(jdbi, ProductV1.class);

        assertThat(plan.changes()).noneMatch(change ->
                "external_note".equalsIgnoreCase(change.column()));
        assertThat(columns(jdbi, TABLE)).contains("EXTERNAL_NOTE");
    }

    @Test
    void applyMode_backfillsExecutionTokenForLegacyActiveProcess() {
        Jdbi jdbi = h2("upg_process_token_backfill");

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
            UUID instanceId = UUID.randomUUID();
            UUID workItemId = UUID.randomUUID();
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

        apply(jdbi);

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

    @Test
    void validateMode_failsOnDrift_passesWhenClean() {
        Jdbi jdbi = h2("upg_validate");
        apply(jdbi, ProductV1.class);

        SchemaUpgrader validator = new SchemaUpgrader(registryOf(ProductWithPhone.class),
                SchemaMode.VALIDATE, false);
        assertThatThrownBy(() -> validator.run(jdbi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("add column")
                .hasMessageContaining("phone");

        apply(jdbi, ProductWithPhone.class);
        assertThatCode(() -> new SchemaUpgrader(registryOf(ProductWithPhone.class),
                SchemaMode.VALIDATE, false).run(jdbi)).doesNotThrowAnyException();
    }

    @Test
    void planMode_reportsWithoutChangingAnything() {
        Jdbi jdbi = h2("upg_plan");
        apply(jdbi, ProductV1.class);

        MigrationPlan plan = new SchemaUpgrader(registryOf(ProductWithPhone.class),
                SchemaMode.PLAN, false).run(jdbi);

        assertThat(plan.isEmpty()).isFalse();
        assertThat(columns(jdbi, TABLE)).doesNotContain("PHONE");
    }

    @Test
    void planAndValidateModesDoNotCreateHistoryTable() {
        Jdbi planned = h2("upg_plan_no_history");
        new SchemaUpgrader(registryOf(ProductV1.class), SchemaMode.PLAN, false).run(planned);
        assertThat(columns(planned, SchemaHistoryRepository.TABLE)).isEmpty();

        Jdbi validated = h2("upg_validate_no_history");
        assertThatThrownBy(() -> new SchemaUpgrader(
                registryOf(ProductV1.class), SchemaMode.VALIDATE, false).run(validated))
                .isInstanceOf(IllegalStateException.class);
        assertThat(columns(validated, SchemaHistoryRepository.TABLE)).isEmpty();
    }

    @Test
    void offMode_doesNothing() {
        Jdbi jdbi = h2("upg_off");
        MigrationPlan plan = new SchemaUpgrader(registryOf(ProductV1.class),
                SchemaMode.OFF, false).run(jdbi);

        assertThat(plan.isEmpty()).isTrue();
        assertThat(columns(jdbi, TABLE)).isEmpty();
    }

    private String isNullable(Jdbi jdbi, String column) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME = :table AND COLUMN_NAME = :column")
                .bind("table", TABLE.toUpperCase())
                .bind("column", column.toUpperCase())
                .mapTo(String.class)
                .one());
    }
}
