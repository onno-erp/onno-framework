package su.onno.ui;

import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves and applies application-provided contextual option decorators.
 */
public final class RefOptionService {

    private final Map<String, RefOptionDecorator> decorators;

    public RefOptionService(List<RefOptionDecorator> decorators) {
        Map<String, RefOptionDecorator> byType = new LinkedHashMap<>();
        for (RefOptionDecorator decorator : decorators) {
            Class<?> type = ClassUtils.getUserClass(decorator);
            byType.put(type.getName(), decorator);
        }
        this.decorators = Map.copyOf(byType);
    }

    public List<Map<String, Object>> decorate(String decoratorType,
                                               RefOptionContext context,
                                               List<Map<String, Object>> rows) {
        RefOptionDecorator decorator = decorators.get(decoratorType);
        if (decorator == null || rows.isEmpty()) {
            return rows;
        }
        List<RefOption> options = rows.stream()
                .map(row -> new RefOption(optionId(row), row))
                .toList();
        Map<UUID, RefOptionDecoration> resolved = decorator.decorate(context, options);
        if (resolved == null || resolved.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> visibleRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RefOptionDecoration decoration = resolved.get(optionId(row));
            if (decoration == null) {
                visibleRows.add(row);
                continue;
            }
            if (decoration.hidden()) {
                continue;
            }
            if (!decoration.badge().isBlank()) {
                row.put("_optionBadge", decoration.badge());
                row.put("_optionTone", decoration.tone().name().toLowerCase());
            }
            if (!decoration.color().isBlank()) {
                row.put("_optionColor", decoration.color());
            }
            if (decoration.disabled()) {
                row.put("_optionDisabled", true);
            }
            if (!decoration.reason().isBlank()) {
                row.put("_optionReason", decoration.reason());
            }
            visibleRows.add(row);
        }
        return visibleRows;
    }

    private static UUID optionId(Map<String, Object> row) {
        Object value = row.get("_id");
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }
}
