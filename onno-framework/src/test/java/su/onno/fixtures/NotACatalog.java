package su.onno.fixtures;

import su.onno.annotations.Catalog;

@Catalog(name = "Bad")
public class NotACatalog {
    // Does not extend CatalogObject — should fail scanning
}
