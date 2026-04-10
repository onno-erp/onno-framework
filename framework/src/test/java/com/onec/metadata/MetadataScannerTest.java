package com.onec.metadata;

import com.onec.fixtures.NotACatalog;
import com.onec.fixtures.TestProduct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MetadataScannerTest {

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scan_validCatalog_returnsCorrectDescriptor() {
        CatalogDescriptor descriptor = scanner.scan(TestProduct.class);

        assertThat(descriptor.logicalName()).isEqualTo("TestProducts");
        assertThat(descriptor.tableName()).isEqualTo("_catalog_TestProducts");
        assertThat(descriptor.codeLength()).isEqualTo(9);
        assertThat(descriptor.javaClass()).isEqualTo(TestProduct.class);
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
}
