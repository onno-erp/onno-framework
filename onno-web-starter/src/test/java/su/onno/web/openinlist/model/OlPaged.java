package su.onno.web.openinlist.model;

import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** An authored Page overrides its list route, so "Open in list" is not offered. */
@Catalog(name = "OlPaged")
public class OlPaged extends CatalogObject {
}
