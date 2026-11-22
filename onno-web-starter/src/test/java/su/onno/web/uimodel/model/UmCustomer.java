package su.onno.web.uimodel.model;

import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** Readable by READER and WRITER, writable by WRITER only. */
@Catalog(name = "UmCustomers", title = "Customers", context = "Sales")
public class UmCustomer extends CatalogObject {
}
