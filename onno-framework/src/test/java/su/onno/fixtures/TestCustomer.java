package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

@Catalog(name = "TestCustomers", codeLength = 8)
@Getter
@Setter
public class TestCustomer extends CatalogObject {

    @Attribute(length = 120)
    private String email;

    @Attribute
    private Ref<TestRegion> region;
}
