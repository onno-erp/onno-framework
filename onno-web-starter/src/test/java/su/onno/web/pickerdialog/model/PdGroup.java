package su.onno.web.pickerdialog.model;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** A hierarchical picker target with a view, a toolbar action and an authored list page. */
@Catalog(name = "PdGroups", hierarchical = true)
public class PdGroup extends CatalogObject {
    @Attribute(length = 40)
    private String region;
}
