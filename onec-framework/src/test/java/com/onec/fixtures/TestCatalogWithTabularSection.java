package com.onec.fixtures;

import com.onec.annotations.Catalog;
import com.onec.annotations.TabularSection;
import com.onec.model.CatalogObject;

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
