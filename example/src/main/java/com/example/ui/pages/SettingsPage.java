package com.example.ui.pages;

import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import org.springframework.stereotype.Component;

/**
 * Settings is just a page. This authored {@code Page} at {@code /settings} is an ordinary page that
 * drops one {@code "setting"} input widget bound to the {@code Store Name} {@link
 * com.example.domain.constants.StoreName @Constant}. The widget loads and saves through
 * SettingsController ({@code /api/settings}, admin-only) — no bespoke editor, just a page with an
 * input, built from the same primitives as any dashboard.
 *
 * <p>Pinned to the {@code admin} profile (like {@link DashboardPage}), so it is ADMIN-only; a MANAGER
 * resolves to the default profile, where this page does not exist. It reaches the sidebar via a
 * {@code .page("/settings", ...)} nav item on {@link com.example.ui.layouts.AdminLayout}.</p>
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

        // The single Store Name knob, edited in place through a generic setting widget bound to the
        // @Constant by its logical name. Add another .widget(...).type("setting") per constant.
        b.widget("Store name").type("setting").width("1/2").order(0)
                .config("constant", "Store Name")
                .hint("The trading name of the shop, shown across the app.");
    }
}
