package su.onno.metadata;

import java.util.Map;

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
        String hint
) {
}
