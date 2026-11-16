package su.onno.mcp;

import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodLock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only MCP visibility of period close: an agent can learn which periods are closed before it
 * tries a backdated write (which the framework would reject with a period-closed error). Closing
 * and reopening stay with the administration page and {@code /api/period-close}.
 */
public class PeriodCloseMcpTools {

    private final PeriodCloseService periods;

    public PeriodCloseMcpTools(PeriodCloseService periods) {
        this.periods = periods;
    }

    @McpTool(name = "period_close_status", title = "Closed periods",
            description = "Lists the accounting periods closed for changes. A document dated on or before"
                    + " closedThrough in a matching scope (global, context:<name>, register:<name>,"
                    + " organization:<id>) cannot be created, changed, deleted, posted or unposted;"
                    + " state 'soft' still admits adjustment documents, 'hard' admits nothing.",
            idempotent = true)
    public Map<String, Object> periodCloseStatus() {
        List<Map<String, Object>> locks = periods.locks().stream().map(this::lockJson).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locks", locks);
        return body;
    }

    private Map<String, Object> lockJson(PeriodLock lock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", lock.scope().toString());
        m.put("label", periods.describe(lock.scope()));
        m.put("closedThrough", lock.closedThrough().toString());
        m.put("state", lock.state());
        return m;
    }
}
