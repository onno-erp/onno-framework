package com.example.domain.catalogs;

import com.onec.annotations.AccessControl;
import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Catalog(name = "Properties", codeLength = 12, codePrefix = "P-", context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Property extends CatalogObject {

    @Attribute(displayName = "Display name", length = 100, required = true)
    private String displayName;

    @Attribute(displayName = "Address", length = 255)
    private String address;

    @Attribute(displayName = "Capacity (adults)")
    private Integer capacityAdults;

    @Attribute(displayName = "Default night rate", precision = 12, scale = 2)
    private BigDecimal defaultNightRate;

    @Attribute(displayName = "Cleaning fee", precision = 12, scale = 2)
    private BigDecimal cleaningFee;

    /** Código de establecimiento assigned by SES.HOSPEDAJES; required to register partes for this property. */
    @Attribute(displayName = "SES establishment code", length = 10)
    @UiHint(order = 5)
    private String sesEstablishmentCode;
}
