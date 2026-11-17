package su.onno.web.relations.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

@Catalog(name = "RlDoctors")
@AccessControl(readRoles = {"WRITER", "LIMITED"}, writeRoles = {"WRITER", "LIMITED"})
public class RlDoctor extends CatalogObject {
}
