package su.onno.web.period;

import su.onno.period.PeriodCloseAuditEntry;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodLock;
import su.onno.period.PeriodScope;
import su.onno.service.AccessService;
import su.onno.service.WebProperties;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Period close over HTTP. Any signed-in user may read the closes in force (so a client can explain
 * a rejection up front); closing, reopening and reading the audit trail need {@code ADMIN} or a
 * role from {@code onno.period-close.manage-roles}.
 *
 * <pre>
 * GET  /api/period-close                 → {locks: [{scope, label, closedThrough, state, updatedAt, updatedBy}], canManage}
 * GET  /api/period-close/audit?limit=100 → [{at, action, scope, previous, closedThrough, actor, reason, …}]
 * POST /api/period-close/close   {scope, closedThrough, hard?, reason?}
 * POST /api/period-close/reopen  {scope, closedThrough?, reason}   (no closedThrough = fully open)
 * </pre>
 *
 * {@code scope} is {@code global}, {@code context:<name>}, {@code register:<name>} or
 * {@code organization:<id>}.
 */
@RestController
@RequestMapping("/api/period-close")
public class PeriodCloseController {

    private final PeriodCloseService periods;
    private final PeriodCloseAccess periodAccess;
    private final AccessService access;
    private final WebProperties properties;

    public PeriodCloseController(PeriodCloseService periods, PeriodCloseAccess periodAccess,
                                 AccessService access, WebProperties properties) {
        this.periods = periods;
        this.periodAccess = periodAccess;
        this.access = access;
        this.properties = properties;
    }

    @GetMapping
    public Map<String, Object> state(Principal principal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locks", periods.locks().stream().map(this::lockJson).toList());
        body.put("canManage", periodAccess.canManage(principal));
        return body;
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit, Principal principal) {
        requireManager(principal);
        return periods.audit(limit).stream().map(PeriodCloseController::auditJson).toList();
    }

    @PostMapping("/close")
    public Map<String, Object> close(@RequestBody Map<String, Object> body, Principal principal) {
        requireManager(principal);
        requireWritable();
        LocalDate closedThrough = date(body.get("closedThrough"));
        if (closedThrough == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "closedThrough is required");
        }
        try {
            return lockJson(periods.close(scope(body), closedThrough, Boolean.parseBoolean(text(body.get("hard"))),
                    access.username(principal), text(body.get("reason"))));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/reopen")
    public Map<String, Object> reopen(@RequestBody Map<String, Object> body, Principal principal) {
        requireManager(principal);
        requireWritable();
        PeriodScope scope = scope(body);
        try {
            return periods.reopen(scope, date(body.get("closedThrough")), access.username(principal),
                            text(body.get("reason")))
                    .map(this::lockJson)
                    .orElseGet(() -> {
                        Map<String, Object> open = new LinkedHashMap<>();
                        open.put("scope", scope.toString());
                        open.put("closedThrough", null);
                        return open;
                    });
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    private void requireManager(Principal principal) {
        if (!periodAccess.canManage(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Closing and reopening periods needs ADMIN or a period-close manage role");
        }
    }

    private void requireWritable() {
        if (properties.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "UI is in read-only mode");
        }
    }

    private static PeriodScope scope(Map<String, Object> body) {
        try {
            return PeriodScope.parse(text(body.get("scope")));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    private static LocalDate date(Object value) {
        String text = text(value);
        if (text == null) return null;
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an ISO date: " + text);
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Map<String, Object> lockJson(PeriodLock lock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", lock.scope().toString());
        m.put("label", periods.describe(lock.scope()));
        m.put("closedThrough", lock.closedThrough().toString());
        m.put("state", lock.state());
        m.put("updatedAt", lock.updatedAt() == null ? null : lock.updatedAt().toString());
        m.put("updatedBy", lock.updatedBy());
        return m;
    }

    private static Map<String, Object> auditJson(PeriodCloseAuditEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.id().toString());
        m.put("at", entry.at().toString());
        m.put("action", entry.action().name());
        m.put("scope", entry.scope().toString());
        m.put("previous", entry.previous() == null ? null : entry.previous().toString());
        m.put("closedThrough", entry.closedThrough() == null ? null : entry.closedThrough().toString());
        m.put("actor", entry.actor());
        m.put("reason", entry.reason());
        if (entry.action() == su.onno.period.PeriodCloseAction.CLOSE) m.put("hard", entry.hard());
        if (entry.operation() != null) {
            m.put("operation", entry.operation().name());
            m.put("documentType", entry.documentType());
            m.put("documentId", entry.documentId() == null ? null : entry.documentId().toString());
            m.put("documentDate", entry.documentDate() == null ? null : entry.documentDate().toString());
        }
        return m;
    }
}
