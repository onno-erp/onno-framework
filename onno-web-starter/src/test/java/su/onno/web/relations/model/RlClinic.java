package su.onno.web.relations.model;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

@Catalog(name = "RlClinics")
public class RlClinic extends CatalogObject {
    @Attribute(length = 60)
    private String city;
}
