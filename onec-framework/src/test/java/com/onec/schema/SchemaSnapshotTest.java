package com.onec.schema;

import com.onec.fixtures.TestInvoice;
import com.onec.fixtures.TestProduct;
import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SchemaSnapshotTest {

    @Test
    void jsonRoundTrip_preservesTablesAndColumnTypes() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestProduct.class));
        registry.registerDocument(scanner.scanDocument(TestInvoice.class));

        SchemaSnapshot snapshot = SchemaSnapshot.of(new SchemaModelBuilder(registry).build());
        SchemaSnapshot parsed = SchemaSnapshot.fromJson(snapshot.toJson());

        assertThat(parsed).isEqualTo(snapshot);
        assertThat(parsed.table("catalog_test_products").column("full_name").type())
                .isEqualTo("VARCHAR(100)");
        assertThat(parsed.table("onec_outbox").column("_payload").notNull()).isTrue();
    }

    @Test
    void jsonCodec_handlesEscapesAndNesting() {
        Map<String, Object> value = Map.of(
                "quote\"backslash\\", List.of("line\nbreak", 42L, true),
                "nested", Map.of("tab\there", "ünïcode"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(Json.write(value));

        assertThat(parsed).isEqualTo(value);
    }

    @Test
    void typeWidening_classification() {
        assertThat(SchemaDiffEngine.isWidening("VARCHAR(100)", "VARCHAR(200)")).isTrue();
        assertThat(SchemaDiffEngine.isWidening("VARCHAR(200)", "VARCHAR(100)")).isFalse();
        assertThat(SchemaDiffEngine.isWidening("VARCHAR(100)", "TEXT")).isTrue();
        assertThat(SchemaDiffEngine.isWidening("INTEGER", "BIGINT")).isTrue();
        assertThat(SchemaDiffEngine.isWidening("BIGINT", "INTEGER")).isFalse();
        assertThat(SchemaDiffEngine.isWidening("DECIMAL(15,2)", "DECIMAL(19,4)")).isTrue();
        assertThat(SchemaDiffEngine.isWidening("DECIMAL(15,2)", "DECIMAL(15,4)")).isFalse();
        assertThat(SchemaDiffEngine.isWidening("VARCHAR(100)", "INTEGER")).isFalse();
    }
}
