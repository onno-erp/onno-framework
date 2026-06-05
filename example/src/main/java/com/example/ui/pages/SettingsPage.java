package com.example.ui.pages;

import com.example.domain.catalogs.Country;
import com.onec.ui.Page;
import com.onec.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * The Settings screen, authored as an ordinary {@link Page} — like the dashboard. It composes the
 * {@code @Constant} editor with the framework's other primitives, so reference data can be managed
 * right here: this page shows the app constants and the Countries catalog side by side. Remove this
 * bean and the framework still renders a default Settings page with just the constant editor.
 */
@Component
public class SettingsPage implements Page {

    @Override
    public String route() {
        return "/settings";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Settings").subtitle("App configuration and reference data.");

        // The @Constant toggles/inputs, saved in place.
        b.constants("App settings");

        // ...and a catalogue managed right here: the full interactive list — New button, the
        // entity's custom action buttons, search/sort, and rows that open a detail beside the page.
        b.list(Country.class);
    }
}
