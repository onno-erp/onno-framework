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
                    maxItems, dateField, titleField, java.util.Map.copyOf(extraConfig));
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

    /** Builds the {@link ShellConfig} — this layout's navigation presentation. */
    public static class ShellBuilder {
        private NavStyle nav;

        /** Nav presentation for this layout's viewport. */
        public ShellBuilder nav(NavStyle style) {
            this.nav = style;
            return this;
        }

        ShellConfig build() {
            return new ShellConfig(nav);
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
            java.util.Map<String, String> extraConfig
    ) {}
}
