package su.onno.spring.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/** Catalog with a required attribute, for exercising required-attribute validation (issue #32). */
@Catalog(name = "TestRequired", codeLength = 9, autoNumber = false)
@Getter
@Setter
public class TestRequiredCatalog extends CatalogObject {

    @Attribute(required = true, length = 120)
    private String name;
}
