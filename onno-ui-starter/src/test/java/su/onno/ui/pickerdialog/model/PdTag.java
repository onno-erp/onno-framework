package su.onno.ui.pickerdialog.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** A picker target with no EntityView: its list surface 404s, yet the dropdown still offers it. */
@Catalog(name = "PdTags")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class PdTag extends CatalogObject {
}
