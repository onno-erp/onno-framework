package com.example.domain.catalogs;

import com.onec.annotations.AccessControl;
import com.onec.annotations.Attribute;
import com.onec.annotations.Catalog;
import com.onec.model.CatalogObject;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A rental unit — a {@code @Catalog}, i.e. stable master data users pick from rather than a business
 * event. {@link CatalogObject} supplies the {@code code} ({@code P-…}) and {@code description}; the
 * fields below add the specifics. Referenced as a {@code Ref<Property>} by
 * {@link com.example.domain.documents.Booking} and {@link com.example.domain.documents.Bill}, and
 * used as the dimension of the Occupancy and Revenue registers. {@code defaultNightRate} pre-fills a
 * new booking's rate.
 */
@Catalog(name = "Properties", codeLength = 12, codePrefix = "P-", context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Property extends CatalogObject {

    @Attribute(displayName = "Display name", length = 100, required = true)
    private String displayName;

    @Attribute(displayName = "Address", length = 255)
    private String address;

    /** Geolocation as a "lat,lng" string; edited via the map picker (.widget("map")). */
    @Attribute(displayName = "Location", length = 40)
    private String location;

    /** A drawn service/coverage area as GeoJSON; edited via the geometry editor (.widget("geojson")). */
    @Attribute(displayName = "Service area", length = 8000)
    private String serviceArea;

    @Attribute(displayName = "Capacity (adults)")
    private Integer capacityAdults;

    @Attribute(displayName = "Default night rate", precision = 12, scale = 2)
    private BigDecimal defaultNightRate;

    @Attribute(displayName = "Cleaning fee", precision = 12, scale = 2)
    private BigDecimal cleaningFee;

    /** Código de establecimiento assigned by SES.HOSPEDAJES; required to register partes for this property. */
    @Attribute(displayName = "SES establishment code", length = 10)
    private String sesEstablishmentCode;
}
