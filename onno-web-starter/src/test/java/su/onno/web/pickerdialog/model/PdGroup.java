package su.onno.web.pickerdialog.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.HierarchicalCatalogObject;

/** A hierarchical picker target with a view, a toolbar action and an authored list page. */
@Catalog(name = "PdGroups", hierarchical = true)
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class PdGroup extends HierarchicalCatalogObject<PdGroup> {
    @Attribute(length = 40)
    private String region;
}
