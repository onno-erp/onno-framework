package su.onno.web.uimodel.model;

import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** Readable by HR only: must never appear in another caller's model. */
@Catalog(name = "UmSalaries", context = "HR")
public class UmSalary extends CatalogObject {
}
