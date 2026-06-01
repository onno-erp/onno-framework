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
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("displayName").order(0)
                .field("address").order(1)
                .field("capacityAdults").order(2)
                .field("defaultNightRate").order(3)
                .field("cleaningFee").order(4);
    }
}
