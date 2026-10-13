package su.onno.ui.calendar;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * The window and filters a calendar widget asks for. {@code from} and {@code to} are inclusive days;
 * {@link #windowStart()}/{@link #windowEnd()} give the equivalent half-open timestamps. Sources may
 * use {@code facets} to narrow their own queries, but need not: the framework drops items whose
 * facet values do not match before responding.
 */
public record CalendarQuery(LocalDate from, LocalDate to, Map<String, Set<String>> facets, Principal principal) {

    public CalendarQuery {
        facets = facets == null ? Map.of() : Map.copyOf(facets);
    }

    public LocalDateTime windowStart() { return from.atStartOfDay(); }

    public LocalDateTime windowEnd() { return to.plusDays(1).atStartOfDay(); }

    /** Selected values for a facet; empty when that filter is not applied. */
    public Set<String> selected(String facet) { return facets.getOrDefault(facet, Set.of()); }

    /** Whether an item passes every applied facet filter. Items without a value fail an applied filter. */
    public boolean matches(CalendarItem item) {
        for (var entry : facets.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            String value = item.facets().get(entry.getKey());
            if (value == null || !entry.getValue().contains(value)) return false;
        }
        return true;
    }
}
