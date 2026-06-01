package com.onec.ui.divkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps a content div in the app chrome as real DivKit, using a content-flow
 * layout (top bar + nav + content, stacked) that DivKit-web lays out reliably —
 * full-viewport flex fills collapse there. The nav is a horizontal, scrollable
 * bar; on mobile it scrolls and paddings tighten. A Flutter client gets the same
 * card and can choose its own chrome treatment per viewport.
 *
 * <p>Navigation/profile/logout intents are {@code onec://} action URLs the host
 * maps to routes, profile re-fetches, and session calls.</p>
 */
public final class ShellLayoutBuilder {

    private ShellLayoutBuilder() {}

    public record ProfileLink(String id, String title) {}

    public record NavItem(String label, String url, boolean active) {}

    public record NavSection(String title, List<NavItem> items) {}

    /**
     * The chrome only (top bar + nav) — no content. The client renders this
     * instantly and streams the per-route content in beneath it, so navigation
     * never blanks while data loads.
     */
    public static Map<String, Object> chrome(String brand,
                                             String userName,
                                             List<ProfileLink> profiles,
                                             String activeProfileId,
                                             List<NavSection> nav,
                                             boolean mobile,
                                             Palette p) {
        Map<String, Object> root = Div.vertical(List.of(
                topbar(brand, userName, profiles, activeProfileId, mobile, p),
                Div.separator(p.border()),
                navBar(nav, mobile, p),
                Div.separator(p.border())));
        Div.matchWidth(root);
        Div.background(root, p.page());
        return root;
    }

    // ----- top bar -----

    private static Map<String, Object> topbar(String brand, String userName,
                                              List<ProfileLink> profiles, String activeProfileId,
                                              boolean mobile, Palette p) {
        List<Map<String, Object>> row = new ArrayList<>();

        String brandLabel = brand == null || brand.isBlank() ? "onec" : brand.toLowerCase();
        Map<String, Object> brandText = Div.text(brandLabel, 17, "bold");
        Div.color(brandText, p.text());
        row.add(brandText);

        Map<String, Object> spacer = Div.weight(Div.horizontal(List.of()), 1);
        row.add(spacer);

        if (!mobile && profiles != null && profiles.size() > 1) {
            for (ProfileLink pl : profiles) {
                row.add(profileChip(pl, pl.id().equals(activeProfileId), p));
            }
        }

        if (!mobile && userName != null && !userName.isBlank()) {
            Map<String, Object> user = Div.text(userName, 13, "regular");
            Div.color(user, p.muted());
            Div.margins(user, 0, 12, 0, 12);
            row.add(user);
        }

        Map<String, Object> themeBtn = Div.text("Theme", 13, "medium");
        Div.color(themeBtn, p.muted());
        Div.pad(themeBtn, 7, 12);
        Div.corner(themeBtn, 8);
        Div.margins(themeBtn, 0, 8, 0, 0);
        Div.action(themeBtn, "theme", "onec://theme/toggle");
        row.add(themeBtn);

        Map<String, Object> logout = Div.text("Sign out", 13, "medium");
        Div.color(logout, p.primary());
        Div.background(logout, p.primarySoft());
        Div.pad(logout, 7, 12);
        Div.corner(logout, 8);
        Div.action(logout, "logout", "onec://logout");
        row.add(logout);

        Map<String, Object> bar = Div.horizontal(row);
        Div.matchWidth(bar);
        Div.pad(bar, 12, mobile ? 16 : 24);
        Div.alignV(bar, "center");
        return bar;
    }

    private static Map<String, Object> profileChip(ProfileLink pl, boolean active, Palette p) {
        Map<String, Object> chip = Div.text(pl.title(), 12, active ? "medium" : "regular");
        Div.color(chip, active ? p.primary() : p.muted());
        if (active) {
            Div.background(chip, p.primarySoft());
        }
        Div.pad(chip, 6, 10);
        Div.corner(chip, 999);
        Div.margins(chip, 0, 6, 0, 0);
        Div.action(chip, "switch-" + pl.id(), "onec://app?profile=" + pl.id());
        return chip;
    }

    // ----- nav bar (horizontal, scrollable) -----

    private static Map<String, Object> navBar(List<NavSection> nav, boolean mobile, Palette p) {
        List<Map<String, Object>> links = new ArrayList<>();
        for (NavSection section : nav) {
            for (NavItem item : section.items()) {
                links.add(navLink(item, p));
            }
        }
        // Plain wrap_content row: as a width-independent child of the vertical root
        // it keeps natural width (no flex-shrink), so labels stay full and there are
        // no gallery scroll arrows. The full-width separator below it reads as the bar.
        Map<String, Object> bar = Div.horizontal(links);
        Div.wrapWidth(bar);
        Div.pad(bar, 6, mobile ? 8 : 16);
        return bar;
    }

    private static Map<String, Object> navLink(NavItem item, Palette p) {
        Map<String, Object> link = Div.text(item.label(), 14, item.active() ? "medium" : "regular");
        Div.maxLines(link, 1);
        Div.color(link, item.active() ? p.primary() : p.text());
        Div.pad(link, 8, 12);
        Div.corner(link, 8);
        Div.margins(link, 0, 2, 0, 2);
        if (item.active()) {
            Div.background(link, p.primarySoft());
        }
        Div.action(link, "nav", item.url());
        return link;
    }
}
