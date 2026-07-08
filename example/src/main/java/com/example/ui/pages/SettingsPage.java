package com.example.ui.pages;

import com.example.domain.catalogs.BookCategory;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * Settings is just a page. This authored {@code Page} at {@code /settings} replaces the framework's
 * built-in constant editor: it still edits the {@code @Constant} values ({@code b.constants(...)}),
 * but composes them alongside inline management of the Book Categories reference data — one surface
 * for "how the shop is configured", built from the same page primitives as any dashboard.
 *
 * <p>Pinned to the {@code admin} profile (like {@link DashboardPage}), so it is ADMIN-only — the same
 * gate the built-in Settings entry uses. Delete this bean and {@code /settings} falls back to the
 * plain constant editor (still enabled in {@code application.yaml}).</p>
 */
@Component
public class SettingsPage implements Page {

    @Override
    public String route() {
        return "/settings";
    }

    @Override
    public String profile() {
        return "admin";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Settings");
        b.subtitle("How Onno Books is configured");

        // The @Constant editor — the single Store Name knob, saved in place.
        b.constants("Store");

        // Reference data managed inline: the same interactive Book Categories list as its own route,
        // embedded here so an admin curates the taxonomy without leaving Settings.
        b.list(BookCategory.class);
    }
}
