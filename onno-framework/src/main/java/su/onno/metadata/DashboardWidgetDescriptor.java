package su.onno.metadata;

import java.util.Map;

/**
 * A resolved dashboard/page widget. {@code rowBreak} forces this widget to start a new layout row
 * even when the previous row still has room (see {@code UiLayoutBuilder.WidgetBuilder#rowBreak()}).
 */
public record DashboardWidgetDescriptor(
        String title,
        String widgetType,
        int order,
        String width,
        String entityType,
        String entityName,
        int maxItems,
        String dateField,
        String titleField,
        Map<String, String> extraConfig,
        String hint,
        boolean rowBreak
) {
    /** Pre-rowBreak shape — kept so existing constructions default to flowing layout. */
    public DashboardWidgetDescriptor(String title, String widgetType, int order, String width,
                                     String entityType, String entityName, int maxItems,
                                     String dateField, String titleField,
                                     Map<String, String> extraConfig, String hint) {
        this(title, widgetType, order, width, entityType, entityName, maxItems, dateField,
                titleField, extraConfig, hint, false);
    }
}
