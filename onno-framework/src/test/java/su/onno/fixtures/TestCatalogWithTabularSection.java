package su.onno.fixtures;

import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.model.CatalogObject;

import java.util.ArrayList;
import java.util.List;

/** A catalog with owned lines for metadata and schema tests. */
@Catalog(name = "TestCatalogWithLines")
@SuppressWarnings({"unused", "FieldMayBeFinal"}) // Metadata fields are read/written reflectively.
public class TestCatalogWithTabularSection extends CatalogObject {

    @TabularSection(name = "lines")
    private List<CatalogLine> lines = new ArrayList<>();
    public static class CatalogLine extends TestInvoiceLine {}

}
