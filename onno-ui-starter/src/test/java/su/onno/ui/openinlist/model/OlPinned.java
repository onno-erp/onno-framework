package su.onno.ui.openinlist.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** Its view promotes "Open in list" and "Duplicate" to primary buttons and hides "Delete". */
@Catalog(name = "OlPinned")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class OlPinned extends CatalogObject {
}
