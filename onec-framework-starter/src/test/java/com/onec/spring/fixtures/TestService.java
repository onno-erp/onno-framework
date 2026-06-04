package com.onec.spring.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A catalog with an {@code @Enumeration} attribute, used to prove enum attributes round-trip through
 * {@code repository.save(...)} / {@code findById(...)} (issue #26).
 */
@Catalog(name = "TestServices", codeLength = 9, autoNumber = false)
@Getter
@Setter
public class TestService extends CatalogObject {

    @Attribute(length = 120)
    private String name;

    @Attribute
    private TestServiceCategory category;
}
