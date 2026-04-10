package com.onec.fixtures;

import com.onec.annotations.Catalog;

@Catalog(name = "Bad")
public class NotACatalog {
    // Does not extend CatalogObject — should fail scanning
}
