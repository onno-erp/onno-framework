package su.onno.spring;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnoNamingStrategyTest {

    private final OnnoNamingStrategy namingStrategy = new OnnoNamingStrategy();
    private final MetadataScanner metadataScanner = new MetadataScanner(new DefaultNamingStrategy());

    @Test
    void tableNameOverridesMatchMetadataStorageTables() {
        assertThat(namingStrategy.getTableName(CustomTableCatalog.class))
                .isEqualTo(metadataScanner.scan(CustomTableCatalog.class).tableName());
        assertThat(namingStrategy.getTableName(CustomTableDocument.class))
                .isEqualTo(metadataScanner.scanDocument(CustomTableDocument.class).tableName());
        assertThat(namingStrategy.getTableName(CustomTableRegister.class))
                .isEqualTo(metadataScanner.scanRegister(CustomTableRegister.class).tableName());
    }

    @Catalog(name = "LogicalCatalog", tableName = "stable_catalog")
    static class CustomTableCatalog extends CatalogObject {
    }

    @Document(name = "LogicalDocument", tableName = "stable_document")
    static class CustomTableDocument extends DocumentObject {
    }

    @AccumulationRegister(name = "LogicalRegister", tableName = "stable_register")
    static class CustomTableRegister extends AccumulationRecord {
    }
}
