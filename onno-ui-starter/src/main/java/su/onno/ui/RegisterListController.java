package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves pages of register data to the virtualized {@code onno-list} island — the register
 * counterpart to {@link ListDataController}. A register is the highest-row-count table in a model,
 * so its movement log (and, for BALANCE registers, its balances) page through here instead of
 * shipping whole: the island fetches one window at a time as the user scrolls. Returns the live
 * total (for the scroll height) plus one window of refs-resolved rows.
 */
@RestController
@RequestMapping("/api/list/registers")
public class RegisterListController {

    private static final int MAX_PAGE = 500;

    private final RegisterQueryService query;
    private final UiAccessService access;

    public RegisterListController(RegisterQueryService query, UiAccessService access) {
        this.query = query;
        this.access = access;
    }

    @GetMapping("/{name}/movements")
    public Map<String, Object> movements(@PathVariable String name,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false) String dir,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        int lim = clamp(limit);
        List<Map<String, Object>> rows = query.movementsPage(desc, from, to, sort, descending(dir), offset, lim);
        return page(query.movementsCount(desc, from, to), offset, rows);
    }

    @GetMapping("/{name}/balance")
    public Map<String, Object> balance(@PathVariable String name,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       Principal principal) {
        AccumulationRegisterDescriptor desc = query.require(name);
        access.requireRead(principal, desc);
        int lim = clamp(limit);
        List<Map<String, Object>> rows = query.balancePage(desc, sort, descending(dir), offset, lim);
        return page(query.balanceCount(desc), offset, rows);
    }

    private static Map<String, Object> page(long total, int offset, List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("offset", offset);
        out.put("rows", rows);
        return out;
    }

    private static boolean descending(String dir) {
        // Registers default newest-/largest-first like the document list (date DESC).
        return dir == null || dir.isBlank() || dir.equalsIgnoreCase("desc");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }
}
