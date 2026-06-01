package com.example.ui.views;

import com.example.domain.catalogs.Country;
import com.onec.ui.EntityConfigBuilder;
import com.onec.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the Country catalog as a visible surface and its field order. List
 * columns fall back to the auto-generated defaults.
 */
@Component
public class CountryView implements EntityView {

    @Override
    public Class<?> entity() {
        return Country.class;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("iso2").order(0)
                .field("name").order(1)
                .field("nationality").order(2);
    }
}
