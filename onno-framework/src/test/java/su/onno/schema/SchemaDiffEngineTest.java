package su.onno.schema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDiffEngineTest {

    @Test
    void dropTables_areOrderedWithChildrenBeforeReferencedParents() {
        Map<String, SchemaSnapshot.TableSnapshot> snapshotTables = new LinkedHashMap<>();
        snapshotTables.put("document_test_invoices", new SchemaSnapshot.TableSnapshot(Map.of()));
        snapshotTables.put("document_test_invoices_items", new SchemaSnapshot.TableSnapshot(Map.of()));
        SchemaSnapshot previous = new SchemaSnapshot(snapshotTables);
        DatabaseIntrospector.DbState db = new DatabaseIntrospector.DbState(
                Map.of(
                        "DOCUMENT_TEST_INVOICES", Set.of("_ID"),
                        "DOCUMENT_TEST_INVOICES_ITEMS", Set.of("_ID", "_PARENT_ID")),
                Map.of("DOCUMENT_TEST_INVOICES_ITEMS", Set.of("DOCUMENT_TEST_INVOICES")));

        MigrationPlan plan = new SchemaDiffEngine(SqlDialect.POSTGRESQL)
                .diff(new SchemaModel(java.util.List.of()), previous, db);

        assertThat(plan.changes())
                .extracting(SchemaChange::table)
                .containsExactly("document_test_invoices_items", "document_test_invoices");
        assertThat(plan.describe())
                .containsSubsequence(
                        "DROP TABLE document_test_invoices_items",
                        "DROP TABLE document_test_invoices");
    }
}
