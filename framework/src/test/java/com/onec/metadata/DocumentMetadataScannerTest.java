package com.onec.metadata;

import com.onec.fixtures.NotADocument;
import com.onec.fixtures.TestInvoice;
import com.onec.fixtures.TestInvoiceLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DocumentMetadataScannerTest {

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scanDocument_validDocument_returnsCorrectDescriptor() {
        DocumentDescriptor descriptor = scanner.scanDocument(TestInvoice.class);

        assertThat(descriptor.logicalName()).isEqualTo("TestInvoices");
        assertThat(descriptor.tableName()).isEqualTo("_document_TestInvoices");
        assertThat(descriptor.numberLength()).isEqualTo(11);
        assertThat(descriptor.javaClass()).isEqualTo(TestInvoice.class);
    }

    @Test
    void scanDocument_validDocument_findsAttributes() {
        DocumentDescriptor descriptor = scanner.scanDocument(TestInvoice.class);

        assertThat(descriptor.attributes()).hasSize(1);

        AttributeDescriptor counterparty = descriptor.attributes().get(0);
        assertThat(counterparty.fieldName()).isEqualTo("counterparty");
        assertThat(counterparty.columnName()).isEqualTo("counterparty");
        assertThat(counterparty.length()).isEqualTo(200);
    }

    @Test
    void scanDocument_validDocument_findsTabularSections() {
        DocumentDescriptor descriptor = scanner.scanDocument(TestInvoice.class);

        assertThat(descriptor.tabularSections()).hasSize(1);

        TabularSectionDescriptor items = descriptor.tabularSections().get(0);
        assertThat(items.name()).isEqualTo("items");
        assertThat(items.fieldName()).isEqualTo("items");
        assertThat(items.tableName()).isEqualTo("_document_TestInvoices_items");
        assertThat(items.rowClass()).isEqualTo(TestInvoiceLine.class);
        assertThat(items.attributes()).hasSize(3);

        assertThat(items.attributes().stream().map(AttributeDescriptor::fieldName))
                .containsExactlyInAnyOrder("productName", "quantity", "price");
    }

    @Test
    void scanDocument_classWithoutAnnotation_throws() {
        assertThatThrownBy(() -> scanner.scanDocument(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not annotated with @Document");
    }

    @Test
    void scanDocument_annotatedClassNotExtendingDocumentObject_throws() {
        assertThatThrownBy(() -> scanner.scanDocument(NotADocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must extend DocumentObject");
    }
}
