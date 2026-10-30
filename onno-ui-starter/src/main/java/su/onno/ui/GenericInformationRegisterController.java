package su.onno.ui;

import su.onno.access.AccessSubject;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generated read API for stored information-register records (including periodic history). */
@RestController
@RequestMapping("/api/information-registers")
public class GenericInformationRegisterController {
    private final InformationRegisterQueryService query;
    private final UiAccessService access;

    public GenericInformationRegisterController(InformationRegisterQueryService query, UiAccessService access) {
        this.query = query;
        this.access = access;
    }

    /**
     * One window of stored records, newest first, in the standard feed envelope
     * {@code {rows, nextCursor, hasMore}}. Pass {@code nextCursor} back as {@code cursor} for the
     * next window; {@code limit} is clamped to 1..500. Every other query parameter is a dimension
     * filter by field name.
     */
    @GetMapping("/{name}/records")
    public Map<String, Object> records(@PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam Map<String, String> params, AccessSubject subject) {
        var desc = query.require(name);
        access.requireRead(subject, desc);
        Map<String, String> filters = new LinkedHashMap<>(params);
        RESERVED.forEach(filters::remove);
        KeysetPage page = query.records(subject, desc, from, to, filters, cursor, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", EntityJsonRepresentation.informationRegisters(desc, page.rows()));
        out.put("nextCursor", page.nextCursor());
        out.put("hasMore", page.hasMore());
        return out;
    }

    private static final List<String> RESERVED = List.of("from", "to", "cursor", "limit");
}
