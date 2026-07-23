package su.onno.ui.divkit;

import su.onno.ui.NavStyle;
import su.onno.ui.UiMessages;

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
 * <p>Navigation/profile/logout intents are {@code onno://} action URLs the host
 * maps to routes, profile re-fetches, and session calls.</p>
 */
public final class ShellLayoutBuilder {

    private ShellLayoutBuilder() {}

    public record ProfileLink(String id, String title) {}

    public record NavItem(String label, String url, String icon, String path) {}

    public record NavSection(String title, String icon, List<NavItem> items) {}

    /**
     * A branding logo for the shell header / mobile menu: the (theme-resolved) image
     * URL plus optional fixed {@code width}/{@code height} in dp. A {@code null} width
     * keeps the intrinsic aspect ratio ({@code wrap_content}); a {@code null} height
     * keeps each surface's default size. {@link #present()} is false for a blank URL, so
     * callers fall back to the text brand.
     */
    public record Logo(String url, Integer width, Integer height) {
        public static Logo of(String url, Integer width, Integer height) {
            return new Logo(url, width, height);
        }

        public boolean present() {
            return url != null && !url.isBlank();
        }
    }

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
     * A nav glyph as an {@code onno-icon} custom block carrying {@code name/color/size};
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
        Map<String, Object> node = Div.custom("onno-icon", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
    }

    /**
     * An {@code onno-icon} that highlights on the active route. The client paints
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
        Map<String, Object> node = Div.custom("onno-icon", props);
        Div.width(node, size);
        Div.height(node, size);
        return node;
    }

    /**
     * The account island: the signed-in user, any persona switcher, and the
     * theme / sign-out actions. On desktop it sits under the nav rail; on mobile
     * it's served as the {@code /account} page (reached from a bottom-bar tab).
     */
    /** Back-compat overload: no avatar, English defaults (used by unit tests). */
    public static Map<String, Object> account(String userName,
                                              List<ProfileLink> profiles,
                                              String activeProfileId,
                                              Palette p) {
        return account(userName, null, profiles, activeProfileId, p, UiMessages.defaults());
    }

    /** Back-compat overload: no avatar. */
    public static Map<String, Object> account(String userName,
                                              List<ProfileLink> profiles,
                                              String activeProfileId,
                                              Palette p,
                                              UiMessages msg) {
        return account(userName, null, profiles, activeProfileId, p, msg);
    }

    public static Map<String, Object> account(String userName,
                                              String avatarUrl,
                                              List<ProfileLink> profiles,
                                              String activeProfileId,
                                              Palette p,
                                              UiMessages msg) {
        List<Map<String, Object>> items = new ArrayList<>();

        List<Map<String, Object>> identityItems = new ArrayList<>();
        if (userName != null && !userName.isBlank()) {
            Map<String, Object> caption = Div.text(msg.get("shell.signedInAs"), 12, "medium");
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
        // The name column takes the slack so the actions stay pinned right whether or not
        // an avatar sits to its left.
        Div.weight(identity, 1);

        String themeIcon = p.equals(Palette.DARK) ? "sun" : "moon";
        Map<String, Object> themeBtn = iconButton(themeIcon, msg.get("shell.theme"), p.muted(), TRANSPARENT, null);
        Div.action(themeBtn, "theme", "onno://theme/toggle");

        Map<String, Object> logout = iconButton("log-out", msg.get("shell.signOut"), p.muted(), TRANSPARENT, null);
        Div.action(logout, "logout", "onno://logout");

        Map<String, Object> actions = Div.horizontal(List.of(themeBtn, logout));
        Div.gap(actions, 2);
        Div.alignV(actions, "center");
        Div.alignH(actions, "right");

        // A leading avatar when the signed-in user's identity record has one; else the row
        // is just the name + actions, exactly as before.
        List<Map<String, Object>> rowCells = new ArrayList<>();
        Map<String, Object> avatar = avatar(avatarUrl, 34, p);
        if (avatar != null) {
            rowCells.add(avatar);
        }
        rowCells.add(identity);
        rowCells.add(actions);

        Map<String, Object> row = Div.horizontal(rowCells);
        Div.gap(row, 8);
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
        Div.corner(root, Radii.CARD);
        Div.stroke(root, p.border(), 1);
        return root;
    }

    /**
     * A circular avatar image for the account block, or {@code null} when the user has none
     * (so the caller degrades to the name-only identity, unchanged). {@code fill} scale crops
     * the image to the square, and the {@code 999} corner rounds it to a circle.
     */
    private static Map<String, Object> avatar(String url, int size, Palette p) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Map<String, Object> img = Div.image(url);
        img.put("scale", "fill");
        Div.width(img, size);
        Div.height(img, size);
        Div.corner(img, 999);
        Div.stroke(img, p.border(), 1);
        return img;
    }

    /** A compact icon action. {@code border} null = no stroke. */
    private static Map<String, Object> iconButton(String iconName, String label, String color, String bg, String border) {
        Map<String, Object> glyph = icon(iconName, color, 20);
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
     *
     * <p>{@code logo} (a configured branding {@link Logo}, or {@code null}) replaces the
     * text {@code brand} in the sidebar header when present.</p>
     */
    /** Back-compat overload rendering the English defaults (used by unit tests). */
    public static Map<String, Object> nav(String brand, Logo logo, List<NavSection> nav, NavStyle style,
                                          boolean compact, Palette p) {
        return nav(brand, logo, nav, style, compact, p, UiMessages.defaults());
    }

    public static Map<String, Object> nav(String brand, Logo logo, List<NavSection> nav, NavStyle style,
                                          boolean compact, Palette p, UiMessages msg) {
        return switch (style) {
            case SIDEBAR -> sidebarNav(brand, logo, nav, p);
            case BOTTOM_BAR -> bottomNav(nav, compact, p, msg);
            case TOPBAR -> topbarNav(nav, p);
        };
    }

    /**
     * A branding logo as a {@code scale: fit} image so it renders uncropped at any
     * proportion. Width defaults to the intrinsic aspect ratio ({@code wrap_content})
     * and height to {@code defaultHeight}; a {@link Logo} may override either with a
     * fixed dp size. The mark doubles as a home affordance — tapping it routes to "/"
     * (an empty {@code onno://} path), the same landing the host resolves for the root.
     */
    private static Map<String, Object> logoImage(Logo logo, int defaultHeight) {
        Map<String, Object> img = Div.image(logo.url());
        img.put("scale", "fit");
        Div.height(img, logo.height() != null ? logo.height() : defaultHeight);
        if (logo.width() != null) {
            Div.width(img, logo.width());
        } else {
            img.put("width", Map.of("type", "wrap_content"));
        }
        Div.action(img, "logo-home", "onno://");
        return img;
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
        Div.action(chip, "switch-" + pl.id(), "onno://app?profile=" + pl.id());
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
        Map<String, Object> glyph = activeIcon(item.icon(), p.text(), p.primary(), item.path(), 14);
        if (glyph != null) {
            link = Div.horizontal(List.of(glyph, label));
            Div.gap(link, 6);
            Div.alignV(link, "center");
        } else {
            link = label;
        }
        Div.pad(link, 8, 12);
        Div.corner(link, Radii.TAB);
        Div.margins(link, 0, 2, 0, 2);
        Div.background(link, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(link, "nav", item.url());
        return link;
    }

    // ----- SIDEBAR: vertical rail beside content -----

    private static Map<String, Object> sidebarNav(String brand, Logo logo, List<NavSection> nav, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        // A configured logo takes the header; otherwise fall back to the text brand.
        // Left offset 10 lines the brand up with the section headers and row text below.
        if (logo != null && logo.present()) {
            Map<String, Object> brandLogo = logoImage(logo, 28);
            Div.margins(brandLogo, 6, 10, 14, 10);
            items.add(brandLogo);
        } else if (brand != null && !brand.isBlank()) {
            Map<String, Object> brandText = Div.text(brand, 14, "medium");
            Div.color(brandText, p.text());
            Div.margins(brandText, 6, 10, 14, 10);
            items.add(brandText);
        }

        boolean anyLinks = false;
        for (NavSection section : nav) {
            boolean titled = section.title() != null && !section.title().isBlank();
            if (titled) {
                items.add(sidebarHeader(section.title(), p));
            }
            for (NavItem item : section.items()) {
                Map<String, Object> link = sidebarLink(item, p);
                // A titleless group after other links (e.g. a trailing Settings) still needs
                // the group breathing room a header would otherwise provide.
                if (!titled && anyLinks && item == section.items().get(0)) {
                    Div.margins(link, 14, 0, 0, 0);
                }
                items.add(link);
                anyLinks = true;
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

    /** A quiet, sentence-case section label; navigation rows below carry the icons. */
    private static Map<String, Object> sidebarHeader(String title, Palette p) {
        Map<String, Object> header = Div.text(title, 13, "regular");
        Div.color(header, p.faint());
        Div.maxLines(header, 1);
        // Left margin matches the links' horizontal padding so headers and rows align;
        // the tall top margin is what visually separates one group from the last.
        Div.margins(header, 18, 8, 4, 10);
        return header;
    }

    private static Map<String, Object> sidebarLink(NavItem item, Palette p) {
        Map<String, Object> label = Div.text(item.label(), 14, "regular");
        Div.maxLines(label, 1);
        Div.color(label, activeColor(item.path(), p.primary(), p.text()));
        Div.weight(label, 1);
        Div.matchWidth(label);

        // Icon + label, like the mobile menu rows and bottom tabs — the icon stays muted,
        // the label carries the active highlight. Degrades to label-only when unauthored.
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, Object> glyph = activeIcon(item.icon(), p.muted(), p.primary(), item.path(), 14);
        if (glyph != null) {
            cells.add(glyph);
        }
        cells.add(label);
        // Ambient presence: a small reserved slot the React bridge fills with who's viewing this entity
        // (empty for non-entity routes or when nobody is here). The whole row is the nav action.
        cells.add(navPresence(item.path()));

        Map<String, Object> row = Div.horizontal(cells);
        Div.matchWidth(row);
        Div.alignV(row, "center");
        Div.gap(row, 8);
        Div.pad(row, 6, 10);
        Div.corner(row, Radii.TAB);
        Div.background(row, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(row, "nav", item.url());
        return row;
    }

    /**
     * The glyph stacked under an {@code onno-notification-dot} custom block in an overlap
     * container. The React bridge paints a small unread dot in the block's top-right corner
     * when there are unread notifications (nothing otherwise, and nothing when the feature is
     * disabled — the client store knows), so the server always emits it and layout never shifts.
     */
    private static Map<String, Object> withUnreadDot(Map<String, Object> glyph, int size) {
        Map<String, Object> dot = Div.custom("onno-notification-dot", Map.of());
        Div.width(dot, size);
        Div.height(dot, size);
        Map<String, Object> stack = Div.container("overlap", List.of(glyph, dot));
        Div.width(stack, size);
        Div.height(stack, size);
        return stack;
    }

    /** A fixed-size {@code onno-nav-presence} custom block — sized like {@link #icon} so the React island
     *  has a measured box to portal into, and so an empty slot never shifts the nav layout. */
    private static Map<String, Object> navPresence(String path) {
        Map<String, Object> node = Div.custom("onno-nav-presence", Map.of("path", path));
        Div.width(node, 56);
        // Matches the avatar size the bridge renders (PresenceAvatars size=20), so the
        // slot never sets the row's height — the label line does.
        Div.height(node, 20);
        return node;
    }

    // ----- BOTTOM_BAR: tab bar pinned below content -----

    // A phone bottom bar fits only a handful of comfortable touch targets, so we show
    // the first few destinations and collapse the rest behind a "More" tab that opens
    // the {@code /menu} hub (full navigation + account). Everything stays reachable
    // without cramming a dozen tiny labels across the width.
    private static final int MAX_PRIMARY_TABS = 4;

    private static Map<String, Object> bottomNav(List<NavSection> nav, boolean compact, Palette p, UiMessages msg) {
        List<NavItem> items = new ArrayList<>();
        for (NavSection section : nav) {
            items.addAll(section.items());
        }

        List<Map<String, Object>> tabs = new ArrayList<>();
        int primary = Math.min(MAX_PRIMARY_TABS, items.size());
        for (NavItem item : items.subList(0, primary)) {
            tabs.add(bottomTab(item, p, compact, false));
        }
        // Always offer "More" — it's the hub for the overflow destinations, the persona
        // switcher, theme toggle, sign-out, and (on mobile) the notification center. It
        // carries the unread-notifications dot so updates stay visible without a bell
        // crowding the bar.
        tabs.add(bottomTab(new NavItem(msg.get("shell.more"), "onno://menu", "menu", "/menu"), p, compact, true));

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

    private static Map<String, Object> bottomTab(NavItem item, Palette p, boolean compact, boolean unreadDot) {
        String color = activeColor(item.path(), p.primary(), p.muted());
        Map<String, Object> label = Div.text(item.label(), 12, "regular");
        Div.maxLines(label, compact ? 1 : 2);
        Div.color(label, color);
        Div.textAlign(label, "center");

        List<Map<String, Object>> stack = new ArrayList<>();
        Map<String, Object> glyph = activeIcon(item.icon(), p.muted(), p.primary(), item.path(), 14);
        if (glyph != null) {
            stack.add(unreadDot ? withUnreadDot(glyph, 14) : glyph);
        }
        stack.add(label);

        Map<String, Object> tab = Div.vertical(stack);
        if (compact) {
            Div.pad(tab, 8, 14);
        } else {
            Div.weight(tab, 1);
            // Tighter horizontal padding than the tablet island — every dp of width is
            // label room when five tabs share a phone screen.
            Div.pad(tab, 8, 2);
        }
        Div.gap(tab, 3);
        Div.corner(tab, Radii.TAB);
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
    /** Back-compat overload: no avatar, English defaults (used by unit tests). */
    public static Map<String, Object> menu(String brand, Logo logo, List<NavSection> nav, String userName,
                                           List<ProfileLink> profiles, String activeProfileId, Palette p) {
        return menu(brand, logo, nav, userName, null, profiles, activeProfileId, p, UiMessages.defaults());
    }

    /** Back-compat overload: no avatar. */
    public static Map<String, Object> menu(String brand, Logo logo, List<NavSection> nav, String userName,
                                           List<ProfileLink> profiles, String activeProfileId, Palette p,
                                           UiMessages msg) {
        return menu(brand, logo, nav, userName, null, profiles, activeProfileId, p, msg);
    }

    /** Back-compat overload: no notifications row. */
    public static Map<String, Object> menu(String brand, Logo logo, List<NavSection> nav, String userName,
                                           String avatarUrl, List<ProfileLink> profiles, String activeProfileId,
                                           Palette p, UiMessages msg) {
        return menu(brand, logo, nav, userName, avatarUrl, profiles, activeProfileId, false, p, msg);
    }

    public static Map<String, Object> menu(String brand, Logo logo, List<NavSection> nav, String userName,
                                           String avatarUrl, List<ProfileLink> profiles, String activeProfileId,
                                           boolean notifications, Palette p, UiMessages msg) {
        List<Map<String, Object>> items = new ArrayList<>();

        // The menu header mirrors the sidebar: a configured logo, else the text brand.
        if (logo != null && logo.present()) {
            Map<String, Object> title = logoImage(logo, 32);
            Div.margins(title, 0, 0, 14, 0);
            items.add(title);
        } else {
            Map<String, Object> title = Div.text(brand != null && !brand.isBlank() ? brand : msg.get("shell.menu"), 24, "medium");
            Div.color(title, p.text());
            Div.margins(title, 0, 0, 14, 0);
            items.add(title);
        }

        // On mobile the bell lives here, not floating over content: a grouped row that opens
        // the notification panel, with the React-filled unread badge pinned right. Emitted
        // only when the notifications feature is wired server-side.
        if (notifications) {
            items.add(menuGroup(List.of(notificationsRow(p, msg)), p));
        }

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

        Map<String, Object> accountBlock = account(userName, avatarUrl, profiles, activeProfileId, p, msg);
        Div.margins(accountBlock, 18, 0, 0, 0);
        items.add(accountBlock);

        Map<String, Object> root = Div.vertical(items);
        Div.id(root, "onno-content");
        Div.matchWidth(root);
        Div.gap(root, 6);
        return root;
    }

    /**
     * The mobile menu's "Notifications" row — shaped exactly like {@link #menuRow} (icon +
     * label on a grouped card) so it reads as part of the hub, with an
     * {@code onno-notification-badge} custom block the React bridge fills with the unread
     * count. Tapping it raises {@code onno://notifications}, which the host maps to opening
     * the notification panel.
     */
    private static Map<String, Object> notificationsRow(Palette p, UiMessages msg) {
        List<Map<String, Object>> cells = new ArrayList<>();
        cells.add(icon("bell", p.muted(), 14));

        Map<String, Object> label = Div.color(Div.text(msg.get("notifications.title"), 14, "regular"), p.text());
        Div.maxLines(label, 1);
        Div.weight(label, 1); // take the remaining width so the badge pins right
        cells.add(label);

        Map<String, Object> badge = Div.custom("onno-notification-badge", Map.of());
        Div.width(badge, 40);
        Div.height(badge, 20);
        cells.add(badge);

        Map<String, Object> row = Div.horizontal(cells);
        Div.gap(row, 12);
        Div.alignV(row, "center");
        Div.pad(row, 15, 14);
        Div.matchWidth(row);
        Div.action(row, "notifications", "onno://notifications");
        return row;
    }

    private static Map<String, Object> menuSectionHeader(String title, Palette p) {
        Map<String, Object> header = Div.text(title, 13, "regular");
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
        Div.corner(card, Radii.CARD);
        Div.stroke(card, p.border(), 1);
        return card;
    }

    private static Map<String, Object> menuRow(NavItem item, Palette p) {
        // Icon + label, left-aligned; the whole full-width row is the tap target and
        // lights up on the active route — affordance enough without a trailing chevron
        // (DivKit grows every flex child equally, so a right-pinned chevron isn't worth
        // the fight). The icon stays muted; the label carries the active highlight.
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, Object> glyph = icon(item.icon(), p.muted(), 14);
        if (glyph != null) {
            cells.add(glyph);
        }
        Map<String, Object> label = Div.color(Div.text(item.label(), 14, "regular"),
                activeColor(item.path(), p.primary(), p.text()));
        Div.maxLines(label, 1);
        Div.weight(label, 1); // take the remaining width so the presence pile pins right
        cells.add(label);
        cells.add(navPresence(item.path())); // ambient presence — the mobile counterpart of the sidebar dots

        Map<String, Object> row = Div.horizontal(cells);
        Div.gap(row, 12);
        Div.alignV(row, "center");
        Div.pad(row, 15, 14);
        Div.matchWidth(row);
        Div.corner(row, Radii.TAB);
        Div.background(row, activeColor(item.path(), p.primarySoft(), TRANSPARENT));
        Div.action(row, "nav", item.url());
        return row;
    }
}
