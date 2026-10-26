package su.onno.metadata;

import su.onno.fixtures.NotACatalog;
import su.onno.fixtures.TestProduct;
import su.onno.fixtures.TestTitledDocument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MetadataScannerTest {

    @su.onno.annotations.Catalog(name = "Unsafe;DROP TABLE catalog_test_products")
    static class UnsafeCatalogName extends su.onno.model.CatalogObject {
    }

    @su.onno.annotations.Catalog(name = "UnsafeAttributeCatalog")
    static class UnsafeAttributeName extends su.onno.model.CatalogObject {
        @SuppressWarnings("unused") // Read by MetadataScanner through reflection.
        @su.onno.annotations.Attribute(name = "unsafe-column")
        private String value;
    }

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scan_validCatalog_returnsCorrectDescriptor() {
        CatalogDescriptor descriptor = scanner.scan(TestProduct.class);

        assertThat(descriptor.logicalName()).isEqualTo("TestProducts");
        assertThat(descriptor.tableName()).isEqualTo("catalog_test_products");
        assertThat(descriptor.codeLength()).isEqualTo(9);
        assertThat(descriptor.javaClass()).isEqualTo(TestProduct.class);
    }

    @Test
    void scan_noTitle_displayTitleFallsBackToName() {
        CatalogDescriptor descriptor = scanner.scan(TestProduct.class);

        assertThat(descriptor.displayTitle()).isEqualTo("TestProducts");
    }

    @Test
    void scanDocument_explicitTitle_keepsNameAndTitleDistinct() {
        DocumentDescriptor descriptor = scanner.scanDocument(TestTitledDocument.class);

        // name stays the URL-safe key; title carries the localized display label.
        assertThat(descriptor.logicalName()).isEqualTo("SupplierOrders");
        assertThat(descriptor.displayTitle()).isEqualTo("Заказы поставщикам");
        assertThat(descriptor.tableName()).isEqualTo("document_supplier_orders");
    }

    @Test
    void scan_validCatalog_findsAllAttributes() {
        CatalogDescriptor descriptor = scanner.scan(TestProduct.class);

        assertThat(descriptor.attributes()).hasSize(3);

        AttributeDescriptor fullName = descriptor.attributes().stream()
                .filter(a -> a.fieldName().equals("fullName"))
                .findFirst().orElseThrow();
        assertThat(fullName.columnName()).isEqualTo("full_name");
        assertThat(fullName.length()).isEqualTo(100);
        assertThat(fullName.isRef()).isFalse();

        AttributeDescriptor unitPrice = descriptor.attributes().stream()
                .filter(a -> a.fieldName().equals("unitPrice"))
                .findFirst().orElseThrow();
        assertThat(unitPrice.columnName()).isEqualTo("unit_price");
        assertThat(unitPrice.precision()).isEqualTo(15);
        assertThat(unitPrice.scale()).isEqualTo(2);
    }

    @Test
    void scan_classWithoutAnnotation_throws() {
        assertThatThrownBy(() -> scanner.scan(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not annotated with @Catalog");
    }

    @Test
    void scan_annotatedClassNotExtendingCatalogObject_throws() {
        assertThatThrownBy(() -> scanner.scan(NotACatalog.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must extend CatalogObject");
    }

    @Test
    void scan_catalogWithTabularSection_findsOwnedLines() {
        var descriptor = scanner.scan(su.onno.fixtures.TestCatalogWithTabularSection.class);
        assertThat(descriptor.tabularSections()).hasSize(1);
        var section = descriptor.tabularSections().getFirst();
        assertThat(section.name()).isEqualTo("lines");
        assertThat(section.tableName()).isEqualTo("catalog_test_catalog_with_lines_lines");
        assertThat(section.rowClass()).isEqualTo(su.onno.fixtures.TestCatalogWithTabularSection.CatalogLine.class);
    }

    @Test
    void scan_rejectsUnsafeTableIdentifierFromAnnotation() {
        assertThatThrownBy(() -> scanner.scan(UnsafeCatalogName.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe SQL identifier")
                .hasMessageContaining("catalog_unsafe;");
    }

    @Test
    void scan_rejectsUnsafeColumnIdentifierFromAnnotation() {
        assertThatThrownBy(() -> scanner.scan(UnsafeAttributeName.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe SQL identifier")
                .hasMessageContaining("unsafe-column");
    }
}
