package su.onno.crm.web;

import java.util.*;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.ListSpec;

/** Applies the ordinary list filter wire protocol after workspace and contact authorization. */
final class CrmInboxFilters {
    private CrmInboxFilters() {}

    static Predicate<Map<String,Object>> predicate(List<ListSpec.Filter> definitions, MultiValueMap<String,String> params) {
        var conditions = new ArrayList<Predicate<Map<String,Object>>>();
        for (String operator : List.of("eq", "in", "like", "prefix", "ge", "le")) {
            var values = new LinkedHashMap<String,List<String>>();
            for (String expression : params.getOrDefault(operator, List.of())) {
                String[] parts = expression.split(",", 2);
                if (parts.length != 2 || definitions.stream().noneMatch(f -> f.field().equals(parts[0]) && allowed(f.type(), operator)))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown inbox filter or operator");
                values.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).add(parts[1]);
            }
            values.forEach((field, choices) -> conditions.add(row -> {
                Object raw = row.get(field);
                if (raw == null) return false;
                String value = raw.toString();
                return choices.stream().anyMatch(choice -> switch (operator) {
                    case "eq", "in" -> value.equals(choice);
                    case "like" -> value.toLowerCase(Locale.ROOT).contains(choice.toLowerCase(Locale.ROOT));
                    case "prefix" -> value.toLowerCase(Locale.ROOT).startsWith(choice.toLowerCase(Locale.ROOT));
                    case "ge" -> value.compareTo(choice) >= 0;
                    case "le" -> value.compareTo(choice.length() == 10 ? choice + "T23:59:59.999999999" : choice) <= 0;
                    default -> false;
                });
            }));
        }
        return row -> conditions.stream().allMatch(condition -> condition.test(row));
    }
    private static boolean allowed(ListSpec.FilterType type, String operator) {
        return switch (type) {
            case OPTIONS -> operator.equals("eq");
            case MULTI_OPTIONS -> operator.equals("in");
            case CONTAINS -> operator.equals("like");
            case STARTS_WITH -> operator.equals("prefix");
            case DATE_RANGE -> operator.equals("ge") || operator.equals("le");
        };
    }
}
