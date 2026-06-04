package com.onec.fixtures;

import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

@Catalog(name = "TestRegions", codeLength = 5)
@Getter
@Setter
public class TestRegion extends CatalogObject {
}
