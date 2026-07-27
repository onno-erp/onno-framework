package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
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

    private static final int MAX_PAGE = 500;
    private static final String CURSOR_PREFIX = "register:";

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
        int rowPosition = rowPosition(request);
        List<Map<String, Object>> rows = query.movementsWindow(desc, from, to, filters,
                sort, descending(dir), rowPosition, lim);
        decorateMovementType(rows);
        return envelope(total, rowPosition, rows);
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
        int rowPosition = rowPosition(request);
        List<Map<String, Object>> rows = query.balanceWindow(desc, filters,
                sort, descending(dir), rowPosition, lim);
        return envelope(total, rowPosition, rows);
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

    /** The window start encoded by the opaque cursor, or zero for the first window. */
    private static int rowPosition(HttpServletRequest request) {
        String cursor = request.getParameter("cursor");
        try {
            if (cursor != null) {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(CURSOR_PREFIX)) {
                    return Math.max(0, Integer.parseInt(decoded.substring(CURSOR_PREFIX.length())));
                }
            }
        } catch (IllegalArgumentException ignored) {
            // A stale/foreign cursor restarts from the top rather than erroring the whole list.
        }
        return 0;
    }

    /** Keyset-style envelope whose cursor keeps the register's implementation position opaque. */
    private static Map<String, Object> envelope(long total, int rowPosition, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        int nextPosition = rowPosition + rows.size();
        boolean hasMore = !rows.isEmpty() && nextPosition < total;
        out.put("rows", rows);
        out.put("nextCursor", hasMore ? cursorFor(nextPosition) : null);
        out.put("hasMore", hasMore);
        out.put("total", total);
        return out;
    }

    private static String cursorFor(int rowPosition) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR_PREFIX + rowPosition).getBytes(StandardCharsets.UTF_8));
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
