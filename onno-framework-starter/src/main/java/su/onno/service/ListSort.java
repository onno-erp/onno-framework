package su.onno.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Ordered, allowlisted list sort keys. A minus prefix reverses an individual key. */
final class ListSort {
    record Term(String column, boolean descending, boolean day, String timeZone) {
        Term(String column, boolean descending) { this(column, descending, false, "UTC"); }
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
            int modifier = field.indexOf(":day");
            boolean day = modifier >= 0 && (modifier + 4 == field.length() || field.charAt(modifier + 4) == '@');
            String timeZone = day && modifier + 4 < field.length() ? field.substring(modifier + 5) : "UTC";
            if (day) {
                try {
                    timeZone = java.time.ZoneId.of(timeZone).getId();
                } catch (java.time.DateTimeException invalid) {
                    throw new ServiceException(ServiceException.Status.BAD_REQUEST, "Invalid sort time zone", invalid);
                }
            }
            String column = resolve.apply(day ? field.substring(0, modifier) : field);
            if (column != null && allowed.contains(column) && seen.add(column)) {
                terms.add(new Term(column, signed ? negative : descending, day, timeZone));
            }
        }
        if (terms.isEmpty() && fallback != null) terms.add(new Term(fallback, fallbackDescending));
        return terms;
    }
}
