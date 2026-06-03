package com.onec.ui;

import java.util.List;

public record UiLayout(List<Section> sections,
                       List<UiLayoutBuilder.WidgetConfig> widgets,
                       List<Profile> profiles,
                       UiIdentityLink identity,
                       ShellConfig shell) {

    public UiLayout {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        shell = shell == null ? ShellConfig.defaults() : shell;
    }

    public UiLayout(List<Section> sections) {
        this(sections, List.of(), List.of(), null, null);
    }

    public UiLayout(List<Section> sections, List<UiLayoutBuilder.WidgetConfig> widgets) {
        this(sections, widgets, List.of(), null, null);
    }

    public UiLayout(List<Section> sections, List<UiLayoutBuilder.WidgetConfig> widgets,
                    List<Profile> profiles) {
        this(sections, widgets, profiles, null, null);
    }

    public UiLayout(List<Section> sections, List<UiLayoutBuilder.WidgetConfig> widgets,
                    List<Profile> profiles, UiIdentityLink identity) {
        this(sections, widgets, profiles, identity, null);
    }

    /**
     * The implicit "default" persona: the top-level sections and widgets, served
     * to any user who matches no named {@link Profile}. Curation layer only —
     * access is still enforced per data endpoint regardless of profile.
     */
    public Profile defaultProfile() {
        return new Profile("default", "", "", List.of(), Integer.MIN_VALUE, sections, widgets);
    }

    public enum Placement {
        SIDEBAR,
        HIDDEN
    }

    /**
     * A named persona bundle: its own navigation ({@code sections}), home
     * ({@code widgets}), branding ({@code title}/{@code theme}) and the
     * {@code roles} that resolve into it. Higher {@code priority} wins when a
     * user matches several. A profile never widens access — it only shapes what
     * a cooperating client renders.
     */
    public record Profile(
            String id,
            String title,
            String theme,
            List<String> roles,
            int priority,
            List<Section> sections,
            List<UiLayoutBuilder.WidgetConfig> widgets
    ) {
        public Profile {
            roles = roles == null ? List.of() : List.copyOf(roles);
            sections = sections == null ? List.of() : List.copyOf(sections);
            widgets = widgets == null ? List.of() : List.copyOf(widgets);
        }
    }

    public record Section(
            String name,
            int order,
            String icon,
            Placement placement,
            List<UiLayoutBuilder.EntityRef> entityRefs
    ) {}

    public record ResolvedSection(
            String name,
            int order,
            String icon,
            String placement,
            List<ResolvedItem> items
    ) {}

    public record ResolvedItem(
            String name,
            String type,
            String href,
            Class<?> javaClass,
            String icon
    ) {
        public ResolvedItem {
            icon = icon == null ? "" : icon;
        }

        /** Back-compat constructor for items with no explicit icon (icon resolved heuristically). */
        public ResolvedItem(String name, String type, String href, Class<?> javaClass) {
            this(name, type, href, javaClass, "");
        }
    }
}
