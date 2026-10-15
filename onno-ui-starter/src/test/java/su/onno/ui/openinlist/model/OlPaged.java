package su.onno.ui.openinlist.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** An authored Page overrides its list route, so "Open in list" is not offered. */
@Catalog(name = "OlPaged")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class OlPaged extends CatalogObject {
}
