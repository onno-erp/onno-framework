package su.onno.crm.web;

import java.util.*;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;

/**
 * Applies the ordinary list filter wire protocol ({@code eq}, {@code in}, {@code like},
 * {@code prefix}, {@code ge}, {@code le} as {@code field,value}) after workspace and contact
 * authorization. Any conversation field may be filtered, with the operators its type supports;
 * which filters the inbox offers is the client's choice.
 */
final class CrmInboxFilters {
    private CrmInboxFilters() {}

    /** What a field can be compared as. */
    enum Kind {
        /** Equality only: ids, references, keys. */
        VALUE,
        /** Equality, contains and starts-with. */
        TEXT,
        /** Equality and an inclusive numeric range. */
        NUMBER,
        /** Equality and an inclusive range over ISO date-times. */
        DATE;

        boolean allows(String operator) {
            return switch (operator) {
                case "eq", "in" -> true;
                case "like", "prefix" -> this == TEXT;
                case "ge", "le" -> this == NUMBER || this == DATE;
                default -> false;
            };
        }
    }

    /** The filterable fields of the conversation catalog (secret attributes excluded), plus facet keys. */
    static Map<String, Kind> fields(CatalogDescriptor conversations, Collection<String> facets) {
        var out = new LinkedHashMap<String, Kind>();
        out.put("description", Kind.TEXT);
        for (AttributeDescriptor attribute : conversations.attributes()) {
            if (!attribute.secret()) out.put(attribute.fieldName(), kind(attribute.javaType()));
        }
        facets.forEach(key -> out.put(key, Kind.VALUE));
        return out;
    }

    private static Kind kind(Class<?> type) {
        if (type == String.class) return Kind.TEXT;
        if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != boolean.class)) return Kind.NUMBER;
        if (java.time.temporal.Temporal.class.isAssignableFrom(type)) return Kind.DATE;
        return Kind.VALUE;
    }

    static Predicate<Map<String,Object>> predicate(Map<String, Kind> fields, MultiValueMap<String,String> params) {
        var conditions = new ArrayList<Predicate<Map<String,Object>>>();
        for (String operator : List.of("eq", "in", "like", "prefix", "ge", "le")) {
            var values = new LinkedHashMap<String,List<String>>();
            for (String expression : params.getOrDefault(operator, List.of())) {
                String[] parts = expression.split(",", 2);
                Kind kind = parts.length == 2 ? fields.get(parts[0]) : null;
                if (kind == null || !kind.allows(operator))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown inbox filter or operator");
                values.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).add(parts[1]);
            }
            values.forEach((field, choices) -> {
                boolean numeric = fields.get(field) == Kind.NUMBER;
                conditions.add(row -> {
                Object raw = row.get(field);
                if (raw == null) return false;
                if(raw instanceof Collection<?> items && (operator.equals("eq") || operator.equals("in"))) return items.stream().anyMatch(item->choices.contains(Objects.toString(item,"")));
                String value = raw.toString();
                if (numeric && !operator.equals("eq") && !operator.equals("in"))
                    return choices.stream().anyMatch(choice -> numberBound(value, choice, operator.equals("le")));
                return choices.stream().anyMatch(choice -> switch (operator) {
                    case "eq", "in" -> value.equals(choice);
                    case "like" -> value.toLowerCase(Locale.ROOT).contains(choice.toLowerCase(Locale.ROOT));
                    case "prefix" -> value.toLowerCase(Locale.ROOT).startsWith(choice.toLowerCase(Locale.ROOT));
                    case "ge" -> value.compareTo(choice) >= 0;
                    case "le" -> value.compareTo(choice.length() == 10 ? choice + "T23:59:59.999999999" : choice) <= 0;
                    default -> false;
                });
            });});
        }
        return row -> conditions.stream().allMatch(condition -> condition.test(row));
    }

    /** A number-range bound compares numerically ("9" below "50"); a non-number never matches. */
    private static boolean numberBound(String value, String bound, boolean upper) {
        try {
            int order = new java.math.BigDecimal(value).compareTo(new java.math.BigDecimal(bound));
            return upper ? order <= 0 : order >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
