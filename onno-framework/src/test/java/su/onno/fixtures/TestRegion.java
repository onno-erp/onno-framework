package su.onno.fixtures;

import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

@Catalog(name = "TestRegions", codeLength = 5)
@Getter
@Setter
public class TestRegion extends CatalogObject {
}
