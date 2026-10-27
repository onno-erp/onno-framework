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
        Map<UUID, RefOptionDecoration> resolved = decorations(decoratorType, context, rows);
        if (resolved.isEmpty()) {
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

    /**
     * The raw decorations the given decorator resolves for {@code rows}, keyed by record id; empty
     * when no decorator is named or registered. The picker dialog (#411) applies them to the list
     * window itself, so it hides and disables exactly what the dropdown would.
     */
    public Map<UUID, RefOptionDecoration> decorations(String decoratorType,
                                                      RefOptionContext context,
                                                      List<Map<String, Object>> rows) {
        // Map.copyOf rejects a null lookup key: a picker without a decorator sends none.
        RefOptionDecorator decorator = decoratorType == null ? null : decorators.get(decoratorType);
        if (decorator == null || rows.isEmpty()) {
            return Map.of();
        }
        List<RefOption> options = rows.stream()
                .map(row -> new RefOption(optionId(row), row))
                .toList();
        Map<UUID, RefOptionDecoration> resolved = decorator.decorate(context, options);
        return resolved == null ? Map.of() : resolved;
    }

    private static UUID optionId(Map<String, Object> row) {
        Object value = row.get("_id");
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }
}
