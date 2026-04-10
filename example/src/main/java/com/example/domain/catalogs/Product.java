package com.example.domain.catalogs;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.annotations.DashboardWidget;
import com.onec.annotations.UiHint;
import com.onec.annotations.UiSection;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import com.example.domain.enumerations.UnitOfMeasure;

import java.math.BigDecimal;

@Catalog(name = "Products", codeLength = 11)
@UiSection(value = "Warehouse", order = 2)
@DashboardWidget(title = "Products", type = "count", order = 10, width = "1/3")
@Getter
@Setter
@ToString(callSuper = true)
public class Product extends CatalogObject {

    @Attribute(displayName = "Full Name", length = 100)
    @UiHint(order = 0)
    private String fullName;

    @Attribute(displayName = "Unit Price", precision = 15, scale = 2)
    @UiHint(order = 1)
    private BigDecimal unitPrice;

    @Attribute(displayName = "Unit of Measure")
    @UiHint(order = 2)
    private UnitOfMeasure unit;
}
