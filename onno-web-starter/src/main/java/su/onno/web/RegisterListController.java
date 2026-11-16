package su.onno.web;

import su.onno.access.AccessSubject;
import su.onno.metadata.AccumulationRegisterDescriptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Serves pages of register data to the virtualized {@code onno-list} island — the register
 * counterpart to {@link ListDataController}. A register is the highest-row-count table in a model,
 * so its movement log (and, for BALANCE registers, its balances) page through here instead of
 * shipping whole: the island fetches one window at a time as the user scrolls.
 *
 * <p>The island scrolls registers through the same
 * {@code {rows, nextCursor, hasMore, total}} envelope as {@link ListDataController} — the opaque
 * cursor identifies the next window because a register list re-sorts freely.</p>
 *
 * <p>The feed honors the grid's declarative filter params ({@code eq}/{@code in}/{@code like}/
 * {@code prefix}/{@code ge}/{@code le}, compiled by {@link ListFilter} against the register's own
 * columns) — the movements tab filters by period range and movement type, and any dimension or
 * resource column is fair game for an authored filter.</p>
 */
@RestController
@RequestMapping("/api/list/registers")
public class RegisterListController {


    /** Movement-type pill colors ({@code _movement_type_color}): receipt green, expense red. */
    private static final String RECEIPT_COLOR = "#16a34a";
    private static final String EXPENSE_COLOR = "#dc2626";

    private final RegisterQueryService query;
    private final AccessService access;
    private final UiMessages messages;

    public RegisterListController(RegisterQueryService query, AccessService access, UiMessages messages) {
        this.query = query;
        this.access = access;
        this.messages = messages;
    }

    @GetMapping("/{name}/movements")
    public Map<String, Object> movements(@PathVariable String name,
                                         @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false) String dir,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         HttpServletRequest request,
                                         AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        int lim = clamp(limit);
        ListFilter.Result filters = filters(request, query.movementFilterColumns(desc), desc);
        var page = query.movementsPage(subject, desc, from, to, filters, sort, descending(dir),
                request.getParameter("cursor"), lim);
        decorateMovementType(page.rows());
        Map<String, Object> out = KeysetWindow.envelope(page);
        if ("exact".equals(request.getParameter("count"))) out.put("total", query.movementsCount(subject, desc, from, to, filters));
        return out;
    }

    @GetMapping("/{name}/balance")
    public Map<String, Object> balance(@PathVariable String name,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       HttpServletRequest request,
                                       AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        int lim = clamp(limit);
        ListFilter.Result filters = filters(request, query.balanceFilterColumns(desc), desc);
        var page = query.balancePage(subject, desc, filters, sort, descending(dir), request.getParameter("cursor"), lim, request.getParameter("asOf"));
        Map<String, Object> out = KeysetWindow.envelope(page);
        if ("exact".equals(request.getParameter("count"))) out.put("total", query.balanceCount(subject, desc, filters, request.getParameter("asOf")));
        return out;
    }

    /**
     * Grouped aggregates over the register's movements for the measured widgets (stat, chart,
     * sparkline, gauge) — the register counterpart of {@code /api/list/{catalogs|documents}/{name}/aggregate},
     * with the same parameters and {@code {buckets, truncated, span?}} envelope. See
     * {@link RegisterQueryService#aggregate}.
     */
    @GetMapping("/{name}/aggregate")
    public Map<String, Object> aggregate(@PathVariable String name, AggregateBuckets.Request request,
                                         AccessSubject subject) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(subject, desc);
        try {
            return query.aggregate(subject, desc, request);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * The grid's declarative filter params, read raw (Spring's {@code List<String>} binding splits
     * a single value on commas, mangling the {@code "column,value"} encoding) and compiled against
     * the register's own columns. Numeric dimensions/resources are passed along so a number-range
     * bound ({@code ge}/{@code le} on a resource) binds as a number.
     */
    private ListFilter.Result filters(HttpServletRequest request, java.util.Set<String> columns,
                                      AccumulationRegisterDescriptor desc) {
        return ListFilter.parse(multi(request, "eq"), multi(request, "in"), multi(request, "like"),
                multi(request, "prefix"), multi(request, "ge"), multi(request, "le"), columns,
                query.numericFilterColumns(desc));
    }

    private static List<String> multi(HttpServletRequest request, String param) {
        String[] values = request.getParameterValues(param);
        return values == null ? List.of() : Arrays.asList(values);
    }

    /**
     * Localize the raw {@code RECEIPT}/{@code EXPENSE} enum into {@code _movement_type_display} and
     * ride a status color in {@code _movement_type_color}, so the grid renders the movement type as
     * the same colored pill an {@code @EnumLabel(color = …)} value gets — instead of a bare enum
     * constant.
     */
    private void decorateMovementType(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object type = row.get("_movement_type");
            if ("RECEIPT".equals(type)) {
                row.put("_movement_type_display", messages.get("register.receipt"));
                row.put("_movement_type_color", RECEIPT_COLOR);
            } else if ("EXPENSE".equals(type)) {
                row.put("_movement_type_display", messages.get("register.expense"));
                row.put("_movement_type_color", EXPENSE_COLOR);
            }
        }
    }

    private static boolean descending(String dir) {
        // Registers default newest-/largest-first like the document list (date DESC).
        return dir == null || dir.isBlank() || dir.equalsIgnoreCase("desc");
    }

    private static int clamp(int limit) {
        return su.onno.query.KeysetWindow.clampLimit(limit);
    }
}
