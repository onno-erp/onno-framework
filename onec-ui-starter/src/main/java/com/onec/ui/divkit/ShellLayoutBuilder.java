package com.onec.ui.divkit;

import com.onec.ui.NavStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the app chrome as two independent DivKit cards — the {@code topbar} (brand
 * + actions) and the {@code nav} — so the client can position them per
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
     * A nav glyph as an {@code onec-icon} custom block carrying {@code name/color/size};
     * the client renders the matching lucide icon by name (see the web client's
     * {@code icon-bridge}). {@code name} is any lucide kebab-case name (e.g.
     * {@code "chart-column"}); an unknown name degrades to a fallback glyph on the client
     * rather than rendering blank. Returns {@code null} when no icon is authored, so
     * callers fall back to a label-only layout.
     */
    private static Map<String, Object> icon(String name, String color, int size) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", name);
        props.put("color", color);
        props.put("size", size);
        Map<String, Object> node = Div.custom("onec-icon", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
    }

    /**
     * An {@code onec-icon} that highlights on the active route. The client paints
     * {@code activeColor} when {@code path} is the current route, else {@code idleColor}
     * — the icon counterpart to {@link #activeColor}, which the React client can't
     * evaluate as a DivKit binding inside a custom block. Returns {@code null} for a
     * blank name so callers degrade to label-only.
     */
    private static Map<String, Object> activeIcon(String name, String idleColor, String activeColor,
                                                  String path, int size) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", name);
        props.put("color", idleColor);
        props.put("activeColor", activeColor);
        props.put("activePath", path);
        props.put("size", size);
        Map<String, Object> node = Div.custom("onec-icon", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
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
        // Outer gap off the screen edges — owned here, not by the web shell's p-3.
        Div.margins(root, 12, 12, 12, 12);
        return root;
    }

    private static Map<String, Object> navLink(NavItem item, Palette p) {
        String color = activeColor(item.path(), p.primary(), p.text());
        Map<String, Object> label = Div.text(item.label(), 14, "regular");
        Div.maxLines(label, 1);
        Div.color(label, color);

        Map<String, Object> link;
        Map<String, Object> glyph = activeIcon(item.icon(), p.text(), p.primary(), item.path(), 16);
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

    // A phone bottom bar fits only a handful of comfortable touch targets, so we show
    // the first few destinations and collapse the rest behind a "More" tab that opens
    // the {@code /menu} hub (full navigation + account). Everything stays reachable
    // without cramming a dozen tiny labels across the width.
    private static final int MAX_PRIMARY_TABS = 4;

    private static Map<String, Object> bottomNav(List<NavSection> nav, boolean compact, Palette p) {
        List<NavItem> items = new ArrayList<>();
        for (NavSection section : nav) {
            items.addAll(section.items());
        }

        List<Map<String, Object>> tabs = new ArrayList<>();
        int primary = Math.min(MAX_PRIMARY_TABS, items.size());
        for (NavItem item : items.subList(0, primary)) {
            tabs.add(bottomTab(item, p, compact));
        }
        // Always offer "More" — it's the hub for the overflow destinations, the persona
        // switcher, theme toggle, and sign-out (none of which fit on the bar itself).
        tabs.add(bottomTab(new NavItem("More", "onec://menu", "menu", "/menu"), p, compact));

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
        // Outer gap off the screen edges — owned here, not by the web shell's p-3.
        Div.margins(bar, 12, 12, 12, 12);
        return bar;
    }

    private static Map<String, Object> bottomTab(NavItem item, Palette p, boolean compact) {
        String color = activeColor(item.path(), p.primary(), p.muted());
        Map<String, Object> label = Div.text(item.label(), 12, "regular");
        Div.maxLines(label, 1);
        Div.color(label, color);
        Div.textAlign(label, "center");

        List<Map<String, Object>> stack = new ArrayList<>();
        Map<String, Object> glyph = activeIcon(item.icon(), p.muted(), p.primary(), item.path(), 20);
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

    // ----- MOBILE MENU: the "More" hub reached from the bottom bar -----

    /**
     * The full-screen mobile menu: every navigation destination grouped by section as
     * tappable rows, with the account block (persona switcher, theme, sign-out) below.
     * It's the overflow target for the bottom bar's "More" tab, so nothing the bar
     * can't fit becomes unreachable.
     */
    public static Map<String, Object> menu(String brand, List<NavSection> nav, String userName,
                                           List<ProfileLink> profiles, String activeProfileId, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        Map<String, Object> title = Div.text(brand != null && !brand.isBlank() ? brand : "Menu", 22, "bold");
        Div.color(title, p.text());
        Div.margins(title, 0, 0, 14, 0);
        items.add(title);

        for (NavSection section : nav) {
            if (section.title() != null && !section.title().isBlank()) {
                items.add(menuSectionHeader(section.title(), p));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NavItem item : section.items()) {
                rows.add(menuRow(item, p));
            }
            if (!rows.isEmpty()) {
                items.add(menuGroup(rows, p));
            }
        }

        Map<String, Object> accountBlock = account(userName, profiles, activeProfileId, p);
        Div.margins(accountBlock, 18, 0, 0, 0);
        items.add(accountBlock);

        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onec-content");
        Div.matchWidth(root);
        Div.gap(root, 6);
        return root;
    }

    private static Map<String, Object> menuSectionHeader(String title, Palette p) {
        Map<String, Object> header = Div.text(title.toUpperCase(), 11, "medium");
        Div.color(header, p.muted());
        Div.maxLines(header, 1);
        Div.margins(header, 14, 4, 6, 4);
        return header;
    }

    // A section's rows on a single bordered surface card, hairline-separated — an
    // iOS-style grouped list that reads cleanly on a narrow screen.
    private static Map<String, Object> menuGroup(List<Map<String, Object>> rows, Palette p) {
        List<Map<String, Object>> withSeparators = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                withSeparators.add(Div.separator(p.border()));
            }
            withSeparators.add(rows.get(i));
        }
        Map<String, Object> card = Div.vertical(withSeparators);
        Div.matchWidth(card);
        Div.background(card, p.surface());
        Div.corner(card, 12);
        Div.stroke(card, p.border(), 1);
        return card;
    }

    private static Map<String, Object> menuRow(NavItem item, Palette p) {
        // Icon + label, left-aligned; the whole full-width row is the tap target and
        // lights up on the active route — affordance enough without a trailing chevron
        // (DivKit grows every flex child equally, so a right-pinned chevron isn't worth
        // the fight). The icon stays muted; the label carries the active highlight.
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, Object> glyph = icon(item.icon(), p.muted(), 18);
        if (glyph != null) {
            cells.add(glyph);
        }
        Map<String, Object> label = Div.color(Div.text(item.label(), 15, "regular"),
                activeColor(item.path(), p.primary(), p.text()));
        Div.maxLines(label, 1);
        cells.add(label);

        Map<String, Object> row = Div.horizontal(cells);
        Div.gap(row, 12);
        Div.alignV(row, "center");
        Div.pad(row, 15, 14);
        Div.matchWidth(row);
        Div.background(row, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(row, "nav", item.url());
        return row;
    }
}
