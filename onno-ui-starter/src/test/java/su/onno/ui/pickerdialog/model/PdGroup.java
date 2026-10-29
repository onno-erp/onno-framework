package su.onno.ui.pickerdialog.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** A hierarchical picker target with a view, a toolbar action and an authored list page. */
@Catalog(name = "PdGroups", hierarchical = true)
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class PdGroup extends CatalogObject {
    @Attribute(length = 40)
    private String region;
}
