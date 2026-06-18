package su.onno.fixtures;

import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.model.CatalogObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Invalid by design: a {@code @Catalog} that declares a {@code @TabularSection}. The framework only
 * generates and round-trips tabular-section tables for documents, so this must be rejected at
 * metadata-scan time rather than failing on the first write (issue #27).
 */
@Catalog(name = "TestCatalogWithLines", codeLength = 9)
public class TestCatalogWithTabularSection extends CatalogObject {

    @TabularSection(name = "lines")
    private List<TestInvoiceLine> lines = new ArrayList<>();
}
