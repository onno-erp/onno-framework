package su.onno.web.pickerdialog.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** A second picker target with no actions: served with its default surface and picker columns. */
@Catalog(name = "PdTags")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class PdTag extends CatalogObject {
}
