package com.example.domain.catalogs;

import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.annotations.DashboardWidget;
import com.onec.annotations.UiHint;
import com.onec.annotations.UiSection;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Catalog(name = "Properties", codeLength = 12, codePrefix = "P-", context = "Rentals")
@UiSection(value = "Rentals", order = 0)
@DashboardWidget(title = "Properties", type = "count", order = 9, width = "1/4")
@Getter
@Setter
public class Property extends CatalogObject {

    @Attribute(displayName = "Display name", length = 100, required = true)
    @UiHint(order = 0)
    private String displayName;

    @Attribute(displayName = "Address", length = 255)
    @UiHint(order = 1)
    private String address;

    @Attribute(displayName = "Capacity (adults)")
    @UiHint(order = 2)
    private Integer capacityAdults;

    @Attribute(displayName = "Default night rate", precision = 12, scale = 2)
    @UiHint(order = 3)
    private BigDecimal defaultNightRate;

    @Attribute(displayName = "Cleaning fee", precision = 12, scale = 2)
    @UiHint(order = 4)
    private BigDecimal cleaningFee;

    /** Código de establecimiento assigned by SES.HOSPEDAJES; required to register partes for this property. */
    @Attribute(displayName = "SES establishment code", length = 10)
    @UiHint(order = 5)
    private String sesEstablishmentCode;
}
