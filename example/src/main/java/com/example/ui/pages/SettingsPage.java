package com.example.ui.pages;

import com.example.domain.catalogs.Country;
import com.onec.ui.ActionResult;
import com.onec.ui.Page;
import com.onec.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * The Settings screen, authored as an ordinary {@link Page} — like the dashboard. It composes the
 * {@code @Constant} editor with the framework's other primitives, so reference data can be managed
 * right here. Everything here is a reusable page primitive: the same toggles, button section, and
 * embedded list drop onto any page (a dashboard, or a catalog page authored at its route) — Settings
 * is just another page. Remove this bean and the framework still renders a default Settings page
 * with the full constant editor.
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

        // Just the toggle(s) you want — not the whole editor — saved in place. Naming the constants
        // lets a single toggle sit on any page; with no names it renders every @Constant.
        b.constants("General", "AutoArchiveEnabled", "CompanyName");

        // A section of buttons that trigger обработка-style server logic, instead of bolting the
        // trigger onto a list toolbar. Each handler runs for an authenticated user (ctx.user()).
        b.actions("Maintenance", a -> {
            a.action("reindex").label("Rebuild search index").icon("refresh-cw")
             .handler(ctx -> ActionResult.message("Reindex started by " + ctx.user()));
            a.action("export").label("Export reference data").icon("download")
             .handler(ctx -> ActionResult.message("Export queued"));
        });

        // ...and a catalogue managed right here: the full interactive list — New button, the
        // entity's custom action buttons, search/sort, and rows that open a detail beside the page.
        b.list(Country.class);
    }
}
