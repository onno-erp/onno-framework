package com.example.domain.catalogs;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Catalog(name = "Products", codeLength = 11)
@Getter
@Setter
@ToString(callSuper = true)
public class Product extends CatalogObject {

    @Attribute(length = 100)
    private String fullName;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Attribute(length = 25)
    private String unit;
}
