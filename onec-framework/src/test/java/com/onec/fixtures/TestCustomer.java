package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;
import com.onec.types.Ref;

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
