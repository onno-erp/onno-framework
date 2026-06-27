package com.example.ui.layouts;

import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;

import org.springframework.stereotype.Component;

/**
 * The <b>admin UI profile</b> — the same shell as {@link MainLayout} plus the two ADMIN-only
 * surfaces: the {@link com.example.domain.catalogs.Employee} catalog in the "People" section, and
 * the dashboard as the home page ({@link com.example.ui.pages.DashboardPage}, which declares
 * {@code profile() == "admin"}).
 *
 * <p>This is what makes "admin gets the dashboard and staff management; manager gets everything
 * else" a hard boundary rather than a hidden link. onno has no per-page RBAC, so a page is gated by
 * living in a role-scoped profile: the active profile is resolved from the caller's roles. This
 * profile is restricted to {@code ADMIN} and given a higher priority, so an ADMIN resolves here
 * (home = dashboard) while a MANAGER can only resolve to the default profile, whose {@code /} is the
 * Orders list. The dashboard page simply does not exist in the manager profile, so it can't be
 * reached by typing the URL.</p>
 *
 * <p>Everything else (list columns, field hints) is inherited: {@code EntityView}s are authored once
 * on the default profile and onno falls back to them when a profile defines none of its own.</p>
 */
@Component
public class AdminLayout implements Layout {

    @Override
    public String profile() {
        return "admin";
    }

    @Override
    public void configure(LayoutSpec layout) {
        // Same shell + sections as the default profile, but with the Employees catalog in "People".
        MainLayout.build(layout, true);
        // Restrict to ADMIN and outrank the default profile, so an ADMIN resolves to this profile
        // (and its dashboard home) rather than the manager baseline.
        layout.roles("ADMIN").priority(10);
    }
}
