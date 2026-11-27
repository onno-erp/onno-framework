package su.onno.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Ordered, allowlisted list sort keys. A minus prefix reverses an individual key. */
final class ListSort {
    record Term(String column, boolean descending, boolean day) {
        Term(String column, boolean descending) { this(column, descending, false); }
    }

    private ListSort() {}

    static List<Term> parse(String input, boolean descending, Function<String, String> resolve,
                            Set<String> allowed, String fallback, boolean fallbackDescending) {
        List<Term> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (input != null) for (String token : input.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) continue;
            boolean negative = value.startsWith("-");
            boolean signed = negative || value.startsWith("+");
            String field = signed ? value.substring(1) : value;
            boolean day = field.endsWith(":day");
            String column = resolve.apply(day ? field.substring(0, field.length() - 4) : field);
            if (column != null && allowed.contains(column) && seen.add(column)) {
                terms.add(new Term(column, signed ? negative : descending, day));
            }
        }
        if (terms.isEmpty() && fallback != null) terms.add(new Term(fallback, fallbackDescending));
        return terms;
    }
}
