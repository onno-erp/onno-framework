package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.EntityJsonRepresentation;
import su.onno.service.InformationRegisterQueryService;
import su.onno.service.KeysetPage;
import su.onno.service.Where;
import su.onno.access.AccessSubject;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generated read API for information registers: the stored records (including periodic history)
 * and the slice of last values. Rows use the logical vocabulary ({@code id}, {@code period}, field
 * names with their reference sidecars); {@code where} is the structured JSON predicate of
 * {@link Where} over dimensions, resources, attributes, {@code id} and {@code period}.
 */
@RestController
@RequestMapping("/api/information-registers")
public class GenericInformationRegisterController {
    private final InformationRegisterQueryService query;
    private final AccessService access;

    public GenericInformationRegisterController(InformationRegisterQueryService query, AccessService access) {
        this.query = query;
        this.access = access;
    }

    /**
     * One window of stored records, newest first, in the standard feed envelope
     * {@code {rows, nextCursor, hasMore}}. Pass {@code nextCursor} back as {@code cursor} for the
     * next window; {@code limit} is clamped to 1..500. Any other parameter is a {@code 400}.
     */
    @GetMapping("/{name}/records")
    public Map<String, Object> records(@PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String where,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam Map<String, String> params, AccessSubject subject) {
        var desc = query.require(name);
        access.requireRead(subject, desc);
        rejectUnknown(params, RECORDS_PARAMS);
        KeysetPage page = query.records(subject, desc, from, to, where, cursor, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", EntityJsonRepresentation.informationRegisters(desc, page.rows()));
        out.put("nextCursor", page.nextCursor());
        out.put("hasMore", page.hasMore());
        return out;
    }

    /**
     * The slice of last values: per dimension tuple, the record in force {@code at} an ISO local
     * date-time (default now), narrowed by {@code where}. A plain list, at most 5000 rows.
     */
    @GetMapping("/{name}/slice-last")
    public List<Map<String, Object>> sliceLast(@PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at,
            @RequestParam(required = false) String where,
            @RequestParam Map<String, String> params, AccessSubject subject) {
        var desc = query.require(name);
        access.requireRead(subject, desc);
        rejectUnknown(params, SLICE_PARAMS);
        return EntityJsonRepresentation.informationRegisters(desc, query.sliceLast(subject, desc, at, where));
    }

    private static void rejectUnknown(Map<String, String> params, Set<String> known) {
        List<String> unknown = params.keySet().stream().filter(k -> !known.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown parameter " + String.join(", ", unknown) + "; filter with where");
        }
    }

    private static final Set<String> RECORDS_PARAMS = Set.of("from", "to", "where", "cursor", "limit");
    private static final Set<String> SLICE_PARAMS = Set.of("at", "where");
}
