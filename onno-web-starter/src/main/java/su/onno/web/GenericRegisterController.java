package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.KeysetWindow;
import su.onno.service.RegisterQueryService;
import su.onno.service.Where;
import su.onno.access.AccessSubject;
import su.onno.metadata.AccumulationRegisterDescriptor;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Generated read API for accumulation registers. Rows use the logical vocabulary of the generated
 * TypeScript model: dimension/resource field names with their reference sidecars
 * ({@code bookDisplay}, {@code bookRef}, …) plus {@code period}, {@code recorder} and
 * {@code movementType} on movements. Every read takes an optional {@code where} — URL-encoded JSON
 * compiled by {@link Where}: over the movement rows for movements and turnover (dimensions,
 * resources, {@code period}, {@code recorder}, {@code movementType}), over the balance rows for a
 * balance (dimensions and the resources' balances).
 *
 * <p>Each read is one keyset window, {@code {rows, nextCursor, hasMore}}: {@code limit} rows
 * (default 100, at most 500) after the opaque {@code cursor} of the previous window. Balance and
 * turnover also accept dimension equality values as plain parameters ({@code ?book=<uuid>}).
 */
@RestController
@RequestMapping("/api/registers")
public class GenericRegisterController {

    private final RegisterQueryService query;
    private final AccessService access;

    public GenericRegisterController(RegisterQueryService query, AccessService access) {
        this.query = query;
        this.access = access;
    }

    @GetMapping("/{name}/movements")
    public Map<String, Object> movements(@PathVariable String name,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String where,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return logical(desc, query.movementsPage(subject, desc, from, to, null, where, null, true, cursor, limit));
    }

    @GetMapping("/{name}/balance")
    public Map<String, Object> balance(@PathVariable String name, @RequestParam Map<String, String> filters,
            @RequestParam(required = false) String asOf,
            @RequestParam(required = false) String where,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return logical(desc, query.balancePage(subject, desc, query.dimensionFilters(desc, filters), where,
                null, false, cursor, limit, asOf));
    }

    @GetMapping("/{name}/turnover")
    public Map<String, Object> turnover(@PathVariable String name,
            @RequestParam String from, @RequestParam String to,
            @RequestParam Map<String, String> allParams,
            @RequestParam(required = false) String where,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit,
            AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        return logical(desc, query.turnoverPage(subject, desc, from, to, allParams, where, cursor, limit));
    }

    /** The keyset envelope with its rows in the logical vocabulary. */
    private static Map<String, Object> logical(AccumulationRegisterDescriptor desc,
                                               su.onno.query.KeysetWindow.Page<Map<String, Object>> page) {
        Map<String, Object> out = KeysetWindow.envelope(page);
        out.put("rows", RegisterQueryService.logical(desc, page.rows()));
        return out;
    }
}
