package com.onec.ui.divkit;

import com.onec.ui.NavStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Emits the app chrome as two independent DivKit cards — the {@link #topbar} (brand
 * + actions) and the {@link #nav} — so the client can position them per
 * {@link NavStyle}: nav as a horizontal {@code TOPBAR}, a vertical {@code SIDEBAR}
 * rail, or a pinned {@code BOTTOM_BAR}. Splitting them lets a sidebar sit beside
 * content and a bottom bar pin below it; a single combined card couldn't do both.
 *
 * <p>Navigation/profile/logout intents are {@code onec://} action URLs the host
 * maps to routes, profile re-fetches, and session calls.</p>
 */
public final class ShellLayoutBuilder {

    private ShellLayoutBuilder() {}

    public record ProfileLink(String id, String title) {}

    public record NavItem(String label, String url, String icon, String path) {}

    public record NavSection(String title, String icon, List<NavItem> items) {}

    /**
     * The DivKit variable holding the current route. Nav item colors bind to it via
     * expressions, so the active highlight follows navigation client-side without
     * re-fetching the shell — the host updates this one variable on each route change.
     */
    public static final String ACTIVE_VAR = "active_path";

    private static final String TRANSPARENT = "#00000000";

    /** A color expression: {@code activeColor} when this item's path is current, else {@code idleColor}. */
    private static String activeColor(String path, String activeColor, String idleColor) {
        return "@{" + ACTIVE_VAR + " == '" + path + "' ? '" + activeColor + "' : '" + idleColor + "'}";
    }

    /**
     * A nav glyph as a {@code div-image} pointing at a bundled monochrome SVG
     * (see {@code public/icons}); {@code tint_color} recolors it to match the
     * item state, so the same asset serves active and idle. Returns {@code null}
     * when no icon is authored, so callers fall back to a label-only layout.
     */
    private static Map<String, Object> icon(String name, String color, int size) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> img = Div.image("/icons/" + name + ".svg");
        Div.width(img, size);
        Div.height(img, size);
        img.put("scale", "fit");
        img.put("tint_color", color);
        return img;
    }

    /**
     * The account island: the signed-in user, any persona switcher, and the
     * theme / sign-out actions. On desktop it sits under the nav rail; on mobile
     * it's served as the {@code /account} page (reached from a bottom-bar tab).
     */
    public static Map<String, Object> account(String userName,
                                              List<ProfileLink> profiles,
                                              String activeProfileId,
                                              Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        List<Map<String, Object>> identityItems = new ArrayList<>();
        if (userName != null && !userName.isBlank()) {
            Map<String, Object> caption = Div.text("Signed in as", 10, "medium");
            Div.color(caption, p.muted());
            Div.maxLines(caption, 1);
            identityItems.add(caption);
            Map<String, Object> user = Div.text(userName, 14, "medium");
            Div.color(user, p.text());
            Div.maxLines(user, 1);
            identityItems.add(user);
        }

        Map<String, Object> identity = Div.vertical(identityItems);
        Div.gap(identity, 2);
        Div.alignV(identity, "center");

        String themeIcon = p.equals(Palette.DARK) ? "sun" : "moon";
        Map<String, Object> themeBtn = iconButton(themeIcon, "Theme", p.muted(), TRANSPARENT, null);
        Div.action(themeBtn, "theme", "onec://theme/toggle");

        Map<String, Object> logout = iconButton("log-out", "Sign out", p.muted(), TRANSPARENT, null);
        Div.action(logout, "logout", "onec://logout");

        Map<String, Object> actions = Div.horizontal(List.of(themeBtn, logout));
        Div.gap(actions, 2);
        Div.alignV(actions, "center");
        Div.alignH(actions, "right");
        Div.weight(actions, 1);

        Map<String, Object> row = Div.horizontal(List.of(identity, actions));
        Div.gap(row, 6);
        Div.alignV(row, "center");
        Div.matchWidth(row);
        items.add(row);

        if (profiles != null && profiles.size() > 1) {
            List<Map<String, Object>> chips = new ArrayList<>();
            for (ProfileLink pl : profiles) {
                chips.add(profileChip(pl, pl.id().equals(activeProfileId), p));
            }
            Map<String, Object> chipRow = Div.horizontal(chips);
            Div.margins(chipRow, 4, 0, 0, 0);
            items.add(chipRow);
        }

        Map<String, Object> root = Div.vertical(items);
        Div.matchWidth(root);
        Div.gap(root, 8);
        Div.pad(root, 9, 10);
        Div.background(root, p.surface());
        Div.corner(root, 12);
        Div.stroke(root, p.border(), 1);
        return root;
    }

    /** A compact icon action. {@code border} null = no stroke. */
    private static Map<String, Object> iconButton(String iconName, String label, String color, String bg, String border) {
        Map<String, Object> glyph = icon(iconName, color, 18);
        Map<String, Object> btn = Div.vertical(List.of(glyph));
        Div.background(btn, bg);
        Div.width(btn, 28);
        Div.height(btn, 28);
        Div.alignH(btn, "center");
        Div.alignV(btn, "center");
        Div.corner(btn, 8);
        if (border != null) {
            Div.stroke(btn, border, 1);
        }
        btn.put("accessibility", Map.of("description", label));
        return btn;
    }

    /**
     * The navigation card, shaped for the chosen {@link NavStyle}. The client
     * positions it (beside / below content); this only decides its inner layout.
     * Built from native DivKit primitives so every official SDK renders it.
     */
    public static Map<String, Object> nav(String brand, List<NavSection> nav, NavStyle style,
                                          boolean compact, Palette p) {
        return switch (style) {
            case SIDEBAR -> sidebarNav(brand, nav, p);
            case BOTTOM_BAR -> bottomNav(nav, compact, p);
            case TOPBAR -> topbarNav(nav, p);
        };
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

    // ----- TOPBAR: horizontal bar above content -----

    private static Map<String, Object> topbarNav(List<NavSection> nav, Palette p) {
        List<Map<String, Object>> links = new ArrayList<>();
        for (NavSection section : nav) {
            for (NavItem item : section.items()) {
                links.add(navLink(item, p));
            }
        }
        // Plain wrap_content row: keeps natural width (no flex-shrink) so labels
        // stay full with no gallery scroll arrows; the separator below reads as the bar.
        Map<String, Object> bar = Div.horizontal(links);
        Div.wrapWidth(bar);
        Div.pad(bar, 6, 16);

        Map<String, Object> root = Div.vertical(List.of(bar, Div.separator(p.border())));
        Div.matchWidth(root);
        Div.background(root, p.page());
        return root;
    }

    private static Map<String, Object> navLink(NavItem item, Palette p) {
        String color = activeColor(item.path(), p.primary(), p.text());
        Map<String, Object> label = Div.text(item.label(), 14, "regular");
        Div.maxLines(label, 1);
        Div.color(label, color);

        Map<String, Object> link;
        Map<String, Object> glyph = icon(item.icon(), color, 16);
        if (glyph != null) {
            link = Div.horizontal(List.of(glyph, label));
            Div.gap(link, 6);
            Div.alignV(link, "center");
        } else {
            link = label;
        }
        Div.pad(link, 8, 12);
        Div.corner(link, 8);
        Div.margins(link, 0, 2, 0, 2);
        Div.background(link, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(link, "nav", item.url());
        return link;
    }

    // ----- SIDEBAR: vertical rail beside content -----

    private static Map<String, Object> sidebarNav(String brand, List<NavSection> nav, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        if (brand != null && !brand.isBlank()) {
            Map<String, Object> brandText = Div.text(brand, 16, "bold");
            Div.color(brandText, p.text());
            Div.margins(brandText, 4, 8, 14, 8);
            items.add(brandText);
        }

        for (NavSection section : nav) {
            if (section.title() != null && !section.title().isBlank()) {
                items.add(sidebarHeader(section.title(), section.icon(), p));
            }
            for (NavItem item : section.items()) {
                items.add(sidebarLink(item, p));
            }
        }
        // Just the content (brand + links) on a surface fill. The host wraps this in
        // the rounded, bordered, scrollable rail island — so the border stays put and
        // only the links scroll when the nav is long (see divkit-view sidebar).
        Map<String, Object> root = Div.vertical(items);
        Div.matchWidth(root);
        Div.background(root, p.surface());
        Div.pad(root, 12, 12);
        Div.gap(root, 2);
        return root;
    }

    private static Map<String, Object> sidebarHeader(String title, String iconName, Palette p) {
        Map<String, Object> header = Div.text(title.toUpperCase(), 11, "medium");
        Div.color(header, p.muted());
        Div.maxLines(header, 1);

        Map<String, Object> node;
        Map<String, Object> glyph = icon(iconName, p.muted(), 14);
        if (glyph != null) {
            node = Div.horizontal(List.of(glyph, header));
            Div.gap(node, 6);
            Div.alignV(node, "center");
        } else {
            node = header;
        }
        Div.margins(node, 12, 8, 4, 8);
        return node;
    }

    private static Map<String, Object> sidebarLink(NavItem item, Palette p) {
        Map<String, Object> link = Div.text(item.label(), 14, "regular");
        Div.maxLines(link, 1);
        Div.color(link, activeColor(item.path(), p.primary(), p.text()));
        Div.matchWidth(link);
        Div.pad(link, 9, 10);
        Div.corner(link, 8);
        Div.background(link, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(link, "nav", item.url());
        return link;
    }

    // ----- BOTTOM_BAR: tab bar pinned below content -----

    private static Map<String, Object> bottomNav(List<NavSection> nav, boolean compact, Palette p) {
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (NavSection section : nav) {
            for (NavItem item : section.items()) {
                tabs.add(bottomTab(item, p, compact));
            }
        }
        // A floating island: rounded, bordered, elevated surface. The host pins it
        // near the bottom edge with margin (see divkit-view bottom_bar). When
        // {@code compact} (tablet), it sizes to its tabs so it can hug a corner;
        // otherwise it stretches and the tabs share the width (phone-portrait).
        Map<String, Object> bar = Div.horizontal(tabs);
        if (compact) {
            Div.wrapWidth(bar);
            Div.gap(bar, 4);
        } else {
            Div.matchWidth(bar);
        }
        Div.pad(bar, 8, 8);
        Div.background(bar, p.surface());
        Div.corner(bar, compact ? 24 : 22);
        Div.stroke(bar, p.border(), 1);
        return bar;
    }

    private static Map<String, Object> bottomTab(NavItem item, Palette p, boolean compact) {
        String color = activeColor(item.path(), p.primary(), p.muted());
        Map<String, Object> label = Div.text(item.label(), 12, "regular");
        Div.maxLines(label, 1);
        Div.color(label, color);
        Div.textAlign(label, "center");

        List<Map<String, Object>> stack = new ArrayList<>();
        Map<String, Object> glyph = icon(item.icon(), color, 20);
        if (glyph != null) {
            stack.add(glyph);
        }
        stack.add(label);

        Map<String, Object> tab = Div.vertical(stack);
        if (compact) {
            Div.pad(tab, 8, 14);
        } else {
            Div.weight(tab, 1);
            Div.pad(tab, 8, 4);
        }
        Div.gap(tab, 3);
        Div.corner(tab, compact ? 18 : 14);
        Div.alignH(tab, "center");
        Div.background(tab, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(tab, "nav", item.url());
        return tab;
    }
}
