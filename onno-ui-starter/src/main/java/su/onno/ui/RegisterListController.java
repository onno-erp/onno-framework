package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves pages of register data to the virtualized {@code onno-list} island — the register
 * counterpart to {@link ListDataController}. A register is the highest-row-count table in a model,
 * so its movement log (and, for BALANCE registers, its balances) page through here instead of
 * shipping whole: the island fetches one window at a time as the user scrolls.
 *
 * <p>The island scrolls registers in infinite mode, so the default response is the same
 * {@code {rows, nextCursor, hasMore, total}} envelope as {@link ListDataController} — the cursor is
 * simply the next window's offset (a register list re-sorts freely, so a true keyset seek doesn't
 * apply; the opaque-cursor contract lets the engine change later without touching the client).
 * Passing {@code ?offset=N} explicitly keeps the legacy {@code {total, offset, rows}} page.</p>
 *
 * <p>Both feeds honor the grid's declarative filter params ({@code eq}/{@code in}/{@code like}/
 * {@code prefix}/{@code ge}/{@code le}, compiled by {@link ListFilter} against the register's own
 * columns) — the movements tab filters by period range and movement type, and any dimension or
 * resource column is fair game for an authored filter.</p>
 */
@RestController
@RequestMapping("/api/list/registers")
public class RegisterListController {

    private static final int MAX_PAGE = 500;

    /** Movement-type pill colors ({@code _movement_type_color}): receipt green, expense red. */
    private static final String RECEIPT_COLOR = "#16a34a";
    private static final String EXPENSE_COLOR = "#dc2626";

    private final RegisterQueryService query;
    private final UiAccessService access;
    private final UiMessages messages;

    public RegisterListController(RegisterQueryService query, UiAccessService access, UiMessages messages) {
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
                                         Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        int lim = clamp(limit);
        ListFilter.Result filters = filters(request, query.movementFilterColumns(desc));
        long total = query.movementsCount(desc, from, to, filters);
        int offset = offsetOf(request);
        List<Map<String, Object>> rows = query.movementsPage(desc, from, to, filters,
                sort, descending(dir), offset, lim);
        decorateMovementType(rows);
        return envelope(request, total, offset, rows);
    }

    @GetMapping("/{name}/balance")
    public Map<String, Object> balance(@PathVariable String name,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       HttpServletRequest request,
                                       Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        int lim = clamp(limit);
        ListFilter.Result filters = filters(request, query.balanceFilterColumns(desc));
        long total = query.balanceCount(desc, filters);
        int offset = offsetOf(request);
        List<Map<String, Object>> rows = query.balancePage(desc, filters,
                sort, descending(dir), offset, lim);
        return envelope(request, total, offset, rows);
    }

    /**
     * The grid's declarative filter params, read raw (Spring's {@code List<String>} binding splits
     * a single value on commas, mangling the {@code "column,value"} encoding) and compiled against
     * the register's own columns.
     */
    private static ListFilter.Result filters(HttpServletRequest request, java.util.Set<String> columns) {
        return ListFilter.parse(multi(request, "eq"), multi(request, "in"), multi(request, "like"),
                multi(request, "prefix"), multi(request, "ge"), multi(request, "le"), columns);
    }

    private static List<String> multi(HttpServletRequest request, String param) {
        String[] values = request.getParameterValues(param);
        return values == null ? List.of() : Arrays.asList(values);
    }

    /** The window start: an explicit {@code ?offset=N} (legacy page), else the echoed cursor, else 0. */
    private static int offsetOf(HttpServletRequest request) {
        String offset = request.getParameter("offset");
        String cursor = request.getParameter("cursor");
        try {
            if (offset != null) return Math.max(0, Integer.parseInt(offset));
            if (cursor != null) return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException ignored) {
            // A stale/foreign cursor restarts from the top rather than erroring the whole list.
        }
        return 0;
    }

    /**
     * Legacy offset page when the client explicitly paged with {@code ?offset}; otherwise the
     * infinite-scroll envelope with the next window's offset riding as the opaque cursor.
     */
    private static Map<String, Object> envelope(HttpServletRequest request, long total, int offset,
                                                List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request.getParameter("offset") != null) {
            out.put("total", total);
            out.put("offset", offset);
            out.put("rows", rows);
            return out;
        }
        boolean hasMore = offset + rows.size() < total;
        out.put("rows", rows);
        out.put("nextCursor", hasMore ? String.valueOf(offset + rows.size()) : null);
        out.put("hasMore", hasMore);
        out.put("total", total);
        return out;
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
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }
}
