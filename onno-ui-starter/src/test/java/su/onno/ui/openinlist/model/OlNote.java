package su.onno.ui.openinlist.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** Default placement: "Open in list" sits in the overflow menu. */
@Catalog(name = "OlNotes")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class OlNote extends CatalogObject {
}
