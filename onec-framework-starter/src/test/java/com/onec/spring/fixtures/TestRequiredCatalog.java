package com.onec.spring.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

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
