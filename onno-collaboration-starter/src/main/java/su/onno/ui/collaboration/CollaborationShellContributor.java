package su.onno.ui.collaboration;

import su.onno.ui.ShellUiContributor;
import su.onno.ui.UiMessages;
import su.onno.ui.divkit.Div;
import su.onno.ui.divkit.Palette;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CollaborationShellContributor implements ShellUiContributor {

    private final boolean notifications;

    CollaborationShellContributor(boolean notifications) {
        this.notifications = notifications;
    }

    @Override
    public Map<String, Object> navTrailing(String path, Palette palette) {
        Map<String, Object> node = Div.custom("onno-nav-presence",
                Map.of("payload", Map.of("path", path)));
        Div.width(node, 56);
        Div.height(node, 20);
        return node;
    }

    @Override
    public Map<String, Object> bottomTabOverlay(String path, Palette palette) {
        if (!notifications || !"/menu".equals(path)) {
            return null;
        }
        Map<String, Object> dot = Div.custom("onno-notification-dot",
                Map.of("payload", Map.of()));
        Div.width(dot, 14);
        Div.height(dot, 14);
        return dot;
    }

    @Override
    public List<Map<String, Object>> mobileMenuItems(Palette palette, UiMessages messages) {
        if (!notifications) {
            return List.of();
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, Object> icon = Div.custom("onno-icon",
                Map.of("name", "bell", "color", palette.muted(), "size", 14));
        Div.width(icon, 14);
        Div.height(icon, 14);
        cells.add(icon);

        Map<String, Object> label = Div.color(
                Div.text(messages.get("notifications.title"), 14, "regular"), palette.text());
        Div.maxLines(label, 1);
        Div.weight(label, 1);
        cells.add(label);

        Map<String, Object> badge = Div.custom("onno-notification-badge",
                Map.of("payload", Map.of()));
        Div.width(badge, 40);
        Div.height(badge, 20);
        cells.add(badge);

        Map<String, Object> row = Div.horizontal(cells);
        Div.gap(row, 12);
        Div.alignV(row, "center");
        Div.pad(row, 15, 14);
        Div.matchWidth(row);
        Div.action(row, "notifications", "onno://notifications");

        Map<String, Object> group = Div.vertical(List.of(row));
        Div.matchWidth(group);
        Div.background(group, palette.surface());
        Div.corner(group, 12);
        Div.stroke(group, palette.border(), 1);
        return List.of(group);
    }
}
