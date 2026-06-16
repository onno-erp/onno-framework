package com.example.ui.views;

import com.example.domain.catalogs.Property;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;
import com.onec.ui.ListSpec;

import org.springframework.stereotype.Component;

/**
 * UI "view" for the Property catalog, authored in code. Takes explicit control of
 * the list columns and their labels — the framework compiles this to DivKit, no
 * frontend code involved. Entities without a view fall back to auto-generated
 * columns, so this is purely additive.
 */
@Component
public class PropertyView implements EntityView {

    @Override
    public Class<?> entity() {
        return Property.class;
    }

    @Override
    public void list(ListSpec list) {
        list.columns("displayName", "address", "capacityAdults", "defaultNightRate", "cleaningFee")
                .label("displayName", "Property")
                .label("capacityAdults", "Sleeps")
                .label("defaultNightRate", "Rate / night")
                .label("cleaningFee", "Cleaning");
        // Offer a Table ⇄ Map toggle; the marker reads the "lat,lng" `location` field, the drawn
        // service area reads the GeoJSON `serviceArea` field, both labelled by the property name.
        list.map().field("location").geoJson("serviceArea").label("displayName");
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("displayName").order(0)
                .field("address").order(1)
                .field("location").order(2).width("full").widget("map")
                        .hint("Pin the property on the map, or type precise lat/lng coordinates.")
                .field("serviceArea").order(3).width("full").widget("geojson")
                        .hint("Draw the property's coverage area (or any paths/points) on the map.")
                .field("capacityAdults").order(4)
                        .hint("Maximum number of adults the property sleeps; used for availability search.")
                .field("defaultNightRate").order(5)
                        .hint("Base rate per night before cleaning fee, taxes, or seasonal multipliers.")
                .field("cleaningFee").order(6)
                .field("sesEstablishmentCode").order(7)
                        .hint("Police/tourism registration code reported with each guest's stay (SES).");
    }
}
