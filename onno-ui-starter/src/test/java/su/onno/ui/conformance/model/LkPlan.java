package su.onno.ui.conformance.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** Unscoped reference data every customer may read. */
@Catalog(name = "LkPlans")
@AccessControl(readRoles = {"CUSTOMER", "STAFF"}, writeRoles = {"STAFF"})
public class LkPlan extends CatalogObject {
}
