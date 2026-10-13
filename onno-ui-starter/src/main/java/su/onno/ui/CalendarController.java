package su.onno.ui;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.calendar.CalendarFacet;
import su.onno.ui.calendar.CalendarItem;
import su.onno.ui.calendar.CalendarMove;
import su.onno.ui.calendar.CalendarQuery;
import su.onno.ui.calendar.CalendarSource;
import su.onno.validation.ValidationException;

/**
 * Serves source-backed calendar widgets ({@code .type("calendar").calendar(name)}): the legend and
 * filters, the merged items for a window, and drag-to-reschedule. Every call re-checks
 * {@link CalendarSource#canRead}/{@link CalendarSource#canReschedule} for the caller, so a hidden or
 * read-only source cannot be reached by crafting requests.
 */
@RestController
@RequestMapping("/api/calendars")
public class CalendarController {
    /** Longest window one request may ask for: the year view's twelve months fit inside. */
    static final int MAX_DAYS = 400;

    private final List<CalendarSource> sources;
    private final List<CalendarFacet> facets;

    public CalendarController(List<CalendarSource> sources, List<CalendarFacet> facets) {
        this.sources = sources.stream().sorted(Comparator.comparingInt(CalendarSource::order)).toList();
        this.facets = facets.stream().sorted(Comparator.comparingInt(CalendarFacet::order)).toList();
    }

    public record SourceView(String key, String label, String color, boolean canReschedule) {}
    public record FacetView(String key, String label, List<CalendarFacet.Option> options, String me) {}
    public record Meta(List<SourceView> sources, List<FacetView> facets, Set<String> watches) {}
    public record ItemView(String id, String source, String title, String subtitle, LocalDateTime start,
                           LocalDateTime end, boolean allDay, String color, String link, String meta,
                           boolean done, boolean tracksCompletion, boolean editable, boolean resizable) {}
    public record MoveRequest(LocalDateTime start, LocalDateTime end, Boolean allDay) {}

    @GetMapping("/{calendar}")
    public Meta meta(@PathVariable String calendar, Principal principal) {
        var readable = readable(calendar, principal);
        var watches = new TreeSet<String>();
        readable.forEach(source -> watches.addAll(source.watches()));
        return new Meta(
                readable.stream().map(s -> new SourceView(s.key(), s.label(), s.color(), s.canReschedule(principal))).toList(),
                facets.stream().filter(f -> f.calendar().equals(calendar))
                        .map(f -> new FacetView(f.key(), f.label(), f.options(principal), f.me(principal)))
                        .filter(f -> !f.options().isEmpty()).toList(),
                watches);
    }

    @GetMapping("/{calendar}/items")
    public List<ItemView> items(@PathVariable String calendar,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(name = "facet", required = false) List<String> facetParams,
                                @RequestParam(name = "source", required = false) Set<String> only,
                                Principal principal) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a range of up to " + MAX_DAYS + " days.");
        }
        var query = new CalendarQuery(from, to, parseFacets(facetParams), principal);
        var result = new ArrayList<ItemView>();
        for (var source : readable(calendar, principal)) {
            if (only != null && !only.isEmpty() && !only.contains(source.key())) continue;
            boolean movable = source.canReschedule(principal);
            for (var item : source.items(query)) {
                if (!item.overlaps(query.windowStart(), query.windowEnd()) || !query.matches(item)) continue;
                result.add(view(source, item, movable));
            }
        }
        result.sort(Comparator.comparing(ItemView::start).thenComparing(ItemView::title));
        return result;
    }

    @PutMapping("/{calendar}/items/{id}")
    public ResponseEntity<Void> reschedule(@PathVariable String calendar, @PathVariable String id,
                                           @RequestBody MoveRequest move, Principal principal) {
        int split = id.indexOf(':');
        if (split <= 0 || move == null || move.start() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown calendar item");
        }
        var source = readable(calendar, principal).stream()
                .filter(s -> s.key().equals(id.substring(0, split))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown calendar item"));
        if (!source.canReschedule(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot reschedule these items");
        }
        if (move.end() != null && !move.end().isAfter(move.start())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end must be after the start");
        }
        source.reschedule(id.substring(split + 1),
                new CalendarMove(move.start(), move.end(), Boolean.TRUE.equals(move.allDay())), principal);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({IllegalArgumentException.class, ValidationException.class, UnsupportedOperationException.class})
    public ResponseEntity<Map<String, String>> refused(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message",
                error.getMessage() == null ? "This change is not allowed" : error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "Request failed" : error.getReason()));
    }

    private List<CalendarSource> readable(String calendar, Principal principal) {
        var forCalendar = sources.stream().filter(s -> s.calendar().equals(calendar)).toList();
        if (forCalendar.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown calendar");
        return forCalendar.stream().filter(s -> s.canRead(principal)).toList();
    }

    private static ItemView view(CalendarSource source, CalendarItem item, boolean movable) {
        return new ItemView(source.key() + ":" + item.id(), source.key(), item.title(), item.subtitle(),
                item.start(), item.end(), item.allDay(), item.color() == null ? source.color() : item.color(),
                item.link(), item.meta(), item.done(), item.tracksCompletion(), movable && item.editable(),
                movable && item.editable() && item.resizable());
    }

    /** {@code facet=owner:<id>} pairs, repeatable; a value may itself contain colons. */
    static Map<String, Set<String>> parseFacets(List<String> params) {
        var result = new LinkedHashMap<String, Set<String>>();
        if (params == null) return result;
        for (String param : params) {
            int split = param.indexOf(':');
            if (split <= 0 || split == param.length() - 1) continue;
            result.computeIfAbsent(param.substring(0, split), k -> new LinkedHashSet<>()).add(param.substring(split + 1));
        }
        return result;
    }
}
