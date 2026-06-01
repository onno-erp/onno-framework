package com.onec.ui.divkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the persona "app shell" as a real DivKit card: title + greeting, a
 * profile switcher, role-filtered navigation, and the home widget list. This is
 * the payload a generic client (web today, Flutter later) fetches on login from
 * {@code GET /api/ui/divkit/app} and renders with an off-the-shelf DivKit SDK.
 *
 * <p>Navigation/switch intents are encoded as {@code onec://} action URLs the
 * client maps to routes and bootstrap re-fetches; the data API is untouched.</p>
 */
public final class AppShellBuilder {

    private AppShellBuilder() {}

    public record ProfileLink(String id, String title) {}

    public record NavItem(String label, String url) {}

    public record NavSection(String title, List<NavItem> items) {}

    public static Map<String, Object> build(String title,
                                            String theme,
                                            String greeting,
                                            String activeProfileId,
                                            List<ProfileLink> profiles,
                                            List<NavSection> sections,
                                            List<String> home) {
        List<Map<String, Object>> items = new ArrayList<>();

        items.add(Div.text(title, 22, "bold"));
        if (greeting != null && !greeting.isBlank()) {
            items.add(Div.withTextColor(Div.text(greeting, 14, "regular"), "#6B7280"));
        }

        if (profiles != null && profiles.size() > 1) {
            List<Map<String, Object>> chips = new ArrayList<>();
            for (ProfileLink p : profiles) {
                boolean active = p.id().equals(activeProfileId);
                Map<String, Object> chip = Div.text(p.title(), 13, active ? "bold" : "regular");
                Div.withAction(chip, "switch-" + p.id(), "onec://app?profile=" + p.id());
                chips.add(chip);
            }
            items.add(Div.horizontal(chips));
        }

        items.add(Div.separator());

        for (NavSection section : sections) {
            items.add(Div.withTextColor(Div.text(section.title(), 12, "medium"), "#6B7280"));
            for (NavItem item : section.items()) {
                Map<String, Object> link = Div.text(item.label(), 16, "regular");
                Div.withAction(link, "nav", item.url());
                items.add(link);
            }
        }

        if (home != null && !home.isEmpty()) {
            items.add(Div.separator());
            items.add(Div.withTextColor(Div.text("Home", 12, "medium"), "#6B7280"));
            for (String widget : home) {
                items.add(Div.text(widget, 15, "regular"));
            }
        }

        Map<String, Object> root = Div.vertical(items);
        List<Map<String, Object>> variables = theme == null || theme.isBlank()
                ? List.of()
                : List.of(DivCard.stringVar("theme", theme));
        return DivCard.of("onec-app", root, variables);
    }
}
