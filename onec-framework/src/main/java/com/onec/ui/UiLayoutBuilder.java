package com.onec.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class UiLayoutBuilder {

    private final Map<String, SectionBuilder> sections = new LinkedHashMap<>();
    private final List<WidgetBuilder> widgets = new ArrayList<>();
    private final Map<String, ProfileBuilder> profiles = new LinkedHashMap<>();
    private final ShellBuilder shell = new ShellBuilder();
    private UiIdentityLink identity;

    /**
     * Configure the app shell — chiefly the navigation presentation. Each layout
     * is authored per {@link Viewport}, so the shell has a single nav style:
     * {@code layout.shell().nav(NavStyle.SIDEBAR)}.
     */
    public ShellBuilder shell() {
        return shell;
    }

    public ShellConfig buildShell() {
        return shell.build();
    }

    public SectionBuilder section(String name) {
        return sections.computeIfAbsent(name, SectionBuilder::new);
    }

    public WidgetBuilder widget(String title) {
        WidgetBuilder wb = new WidgetBuilder(this, title);
        widgets.add(wb);
        return wb;
    }

    /**
     * Declare (or extend) a named persona profile. Its {@code section(...)} and
     * {@code widget(...)} calls are scoped to the profile and do not affect the
     * default layout. See {@link UiLayout.Profile}.
     */
    public ProfileBuilder profile(String id) {
        return profiles.computeIfAbsent(id, ProfileBuilder::new);
    }

    public List<UiLayout.Profile> buildProfiles() {
        List<UiLayout.Profile> result = new ArrayList<>();
        for (ProfileBuilder pb : profiles.values()) {
            result.add(pb.buildProfile());
        }
        return result;
    }

    /**
     * Link authenticated accounts to a catalog record by matching the login to
     * {@code loginField}, so persona UIs can resolve "the current person".
     * See {@link UiIdentityLink}.
     */
    public UiLayoutBuilder identity(Class<?> directoryClass, String loginField) {
        this.identity = new UiIdentityLink(directoryClass, loginField);
        return this;
    }

    public UiIdentityLink buildIdentity() {
        return identity;
    }

    public List<UiLayout.Section> build() {
        List<UiLayout.Section> result = new ArrayList<>();
        for (SectionBuilder sb : sections.values()) {
            result.add(sb.build());
        }
        result.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return result;
    }

    public List<WidgetConfig> buildWidgets() {
        List<WidgetConfig> result = new ArrayList<>();
        for (WidgetBuilder wb : widgets) {
            result.add(wb.build());
        }
        result.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return result;
    }

    public boolean hasWidgets() {
        return !widgets.isEmpty();
    }

    public static class SectionBuilder {
        private final String name;
        private int order = 999;
        private String icon = "";
        private UiLayout.Placement placement = UiLayout.Placement.SIDEBAR;
        private final List<EntityRef> entities = new ArrayList<>();

        SectionBuilder(String name) {
            this.name = name;
        }

        public SectionBuilder order(int order) {
            this.order = order;
            return this;
        }

        public SectionBuilder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public SectionBuilder placement(UiLayout.Placement placement) {
            this.placement = placement;
            return this;
        }

        public SectionBuilder catalog(Class<?> clazz) {
            entities.add(new EntityRef("catalog", clazz));
            return this;
        }

        public SectionBuilder catalog(Class<?> clazz, Consumer<EntityConfigBuilder> configurer) {
            return entity("catalog", clazz, configurer);
        }

        /** Add a catalog with an explicit nav icon (a lucide icon name, honored over the heuristic). */
        public SectionBuilder catalog(Class<?> clazz, String icon) {
            entities.add(new EntityRef("catalog", clazz, Map.of(), icon));
            return this;
        }

        public SectionBuilder document(Class<?> clazz) {
            entities.add(new EntityRef("document", clazz));
            return this;
        }

        public SectionBuilder document(Class<?> clazz, Consumer<EntityConfigBuilder> configurer) {
            return entity("document", clazz, configurer);
        }

        /** Add a document with an explicit nav icon (a lucide icon name, honored over the heuristic). */
        public SectionBuilder document(Class<?> clazz, String icon) {
            entities.add(new EntityRef("document", clazz, Map.of(), icon));
            return this;
        }

        public SectionBuilder register(Class<?> clazz) {
            entities.add(new EntityRef("register", clazz));
            return this;
        }

        public SectionBuilder register(Class<?> clazz, Consumer<EntityConfigBuilder> configurer) {
            return entity("register", clazz, configurer);
        }

        /** Add a register with an explicit nav icon (a lucide icon name, honored over the heuristic). */
        public SectionBuilder register(Class<?> clazz, String icon) {
            entities.add(new EntityRef("register", clazz, Map.of(), icon));
            return this;
        }

        private SectionBuilder entity(String type, Class<?> clazz,
                                       Consumer<EntityConfigBuilder> configurer) {
            EntityConfigBuilder cfg = new EntityConfigBuilder();
            configurer.accept(cfg);
            entities.add(new EntityRef(type, clazz, cfg.buildFieldHints(), cfg.buildIcon()));
            return this;
        }

        UiLayout.Section build() {
            return new UiLayout.Section(name, order, icon, placement, List.copyOf(entities));
        }
    }

    public static class WidgetBuilder {
        private final UiLayoutBuilder parent;
        private String title;
        private String type = "count";
        private int order = 0;
        private String width = "1/3";
        private Class<?> entityClass;
        private String entityType;
        private int maxItems = 10;
        private final java.util.Map<String, String> extraConfig = new java.util.LinkedHashMap<>();
        private String dateField = "";
        private String titleField = "";
        private String hint = "";

        WidgetBuilder(UiLayoutBuilder parent, String title) {
            this.parent = parent;
            this.title = title;
        }

        public WidgetBuilder type(String type) {
            this.type = type;
            return this;
        }

        public WidgetBuilder order(int order) {
            this.order = order;
            return this;
        }

        public WidgetBuilder width(String width) {
            this.width = width;
            return this;
        }

        public WidgetBuilder catalog(Class<?> clazz) {
            this.entityClass = clazz;
            this.entityType = "catalog";
            return this;
        }

        public WidgetBuilder document(Class<?> clazz) {
            this.entityClass = clazz;
            this.entityType = "document";
            return this;
        }

        public WidgetBuilder register(Class<?> clazz) {
            this.entityClass = clazz;
            this.entityType = "register";
            return this;
        }

        public WidgetBuilder maxItems(int maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public WidgetBuilder dateField(String dateField) {
            this.dateField = dateField;
            return this;
        }

        public WidgetBuilder titleField(String titleField) {
            this.titleField = titleField;
            return this;
        }

        public WidgetBuilder config(String key, String value) {
            this.extraConfig.put(key, value);
            return this;
        }

        /** Optional help text, surfaced as a hoverable {@code ?} icon next to the widget title. */
        public WidgetBuilder hint(String hint) {
            this.hint = hint;
            return this;
        }

        /** Start a new widget. */
        public WidgetBuilder widget(String title) {
            return parent.widget(title);
        }

        /** Switch back to section configuration. */
        public SectionBuilder section(String name) {
            return parent.section(name);
        }

        WidgetConfig build() {
            return new WidgetConfig(title, type, order, width, entityClass, entityType,
                    maxItems, dateField, titleField, java.util.Map.copyOf(extraConfig), hint);
        }
    }

    /**
     * Configures a named persona profile. Inherits {@code section(...)} and
     * {@code widget(...)} from {@link UiLayoutBuilder} (scoped to this profile)
     * and adds persona metadata: target {@code roles}, branding and match
     * {@code priority}.
     */
    public static class ProfileBuilder extends UiLayoutBuilder {
        private final String id;
        private String title = "";
        private String theme = "";
        private int priority = 0;
        private final List<String> roles = new ArrayList<>();

        ProfileBuilder(String id) {
            this.id = id;
        }

        public ProfileBuilder title(String title) {
            this.title = title;
            return this;
        }

        public ProfileBuilder theme(String theme) {
            this.theme = theme;
            return this;
        }

        public ProfileBuilder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Roles that resolve a user into this profile. Empty means "all users". */
        public ProfileBuilder roles(String... roles) {
            this.roles.addAll(List.of(roles));
            return this;
        }

        UiLayout.Profile buildProfile() {
            return new UiLayout.Profile(id, title, theme, List.copyOf(roles), priority,
                    build(), buildWidgets());
        }
    }

    /**
     * Builds the {@link ShellConfig} — this layout's navigation presentation and
     * {@link BrandingConfig} (app name, logo, favicon, brand palette).
     */
    public static class ShellBuilder {
        private NavStyle nav;
        private String appName;
        private String logoUrl;
        private String logoUrlDark;
        private Integer logoWidth;
        private Integer logoHeight;
        private String faviconUrl;
        private final PaletteBuilder light = new PaletteBuilder();
        private final PaletteBuilder dark = new PaletteBuilder();

        /** Nav presentation for this layout's viewport. */
        public ShellBuilder nav(NavStyle style) {
            this.nav = style;
            return this;
        }

        /** Explicit application name, shown in the shell instead of the profile title. */
        public ShellBuilder brand(String appName) {
            this.appName = appName;
            return this;
        }

        /** Logo image (URL or served asset) rendered in the sidebar header and mobile menu. */
        public ShellBuilder logo(String url) {
            this.logoUrl = url;
            return this;
        }

        /** Logo with a distinct dark-mode variant. */
        public ShellBuilder logo(String light, String dark) {
            this.logoUrl = light;
            this.logoUrlDark = dark;
            return this;
        }

        /** Fixed logo width in dp; unset keeps the intrinsic aspect ratio (wrap_content). */
        public ShellBuilder logoWidth(int width) {
            this.logoWidth = width;
            return this;
        }

        /** Fixed logo height in dp; unset keeps each surface's default (sidebar 28, mobile menu 32). */
        public ShellBuilder logoHeight(int height) {
            this.logoHeight = height;
            return this;
        }

        /** Fixed logo box in dp. With {@code scale: fit} the mark stays uncropped within it. */
        public ShellBuilder logoSize(int width, int height) {
            this.logoWidth = width;
            this.logoHeight = height;
            return this;
        }

        /** Favicon (URL or served asset) the web client installs at runtime. */
        public ShellBuilder favicon(String url) {
            this.faviconUrl = url;
            return this;
        }

        /** Override brand colors for light mode (only the set slots; the rest keep the default scale). */
        public ShellBuilder light(java.util.function.Consumer<PaletteBuilder> overrides) {
            overrides.accept(light);
            return this;
        }

        /** Override brand colors for dark mode (only the set slots; the rest keep the default scale). */
        public ShellBuilder dark(java.util.function.Consumer<PaletteBuilder> overrides) {
            overrides.accept(dark);
            return this;
        }

        ShellConfig build() {
            BrandingConfig branding = new BrandingConfig(
                    appName, logoUrl, logoUrlDark, logoWidth, logoHeight, faviconUrl,
                    light.build(), dark.build());
            return new ShellConfig(nav, branding);
        }
    }

    /** Typed brand color overrides for one mode; unset slots stay {@code null} (renderer default). */
    public static class PaletteBuilder {
        private String page;
        private String surface;
        private String border;
        private String text;
        private String muted;
        private String primary;
        private String primarySoft;

        /** App background behind the islands. */
        public PaletteBuilder page(String color) {
            this.page = color;
            return this;
        }

        /** Card / panel fill. */
        public PaletteBuilder surface(String color) {
            this.surface = color;
            return this;
        }

        /** Hairline strokes and separators. */
        public PaletteBuilder border(String color) {
            this.border = color;
            return this;
        }

        /** Primary foreground text. */
        public PaletteBuilder text(String color) {
            this.text = color;
            return this;
        }

        /** Secondary / caption foreground. */
        public PaletteBuilder muted(String color) {
            this.muted = color;
            return this;
        }

        /** Brand accent (active nav, links, primary buttons). */
        public PaletteBuilder primary(String color) {
            this.primary = color;
            return this;
        }

        /** The tint painted behind the active/selected accent. */
        public PaletteBuilder primarySoft(String color) {
            this.primarySoft = color;
            return this;
        }

        BrandPalette build() {
            return new BrandPalette(page, surface, border, text, muted, primary, primarySoft);
        }
    }

    public record EntityRef(String type, Class<?> javaClass, Map<String, FieldHint> fieldHints, String icon) {
        public EntityRef {
            fieldHints = fieldHints == null ? Map.of() : Map.copyOf(fieldHints);
            icon = icon == null ? "" : icon;
        }

        /** Convenience constructor for callers that don't provide field hints. */
        public EntityRef(String type, Class<?> javaClass) {
            this(type, javaClass, Map.of(), "");
        }

        /** Convenience constructor for callers that provide hints but no explicit icon. */
        public EntityRef(String type, Class<?> javaClass, Map<String, FieldHint> fieldHints) {
            this(type, javaClass, fieldHints, "");
        }
    }

    public record WidgetConfig(
            String title,
            String type,
            int order,
            String width,
            Class<?> entityClass,
            String entityType,
            int maxItems,
            String dateField,
            String titleField,
            java.util.Map<String, String> extraConfig,
            String hint
    ) {}
}
