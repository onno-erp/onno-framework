package su.onno.ui.period;

import su.onno.period.PeriodCloseAction;
import su.onno.period.PeriodCloseAuditEntry;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodLock;
import su.onno.period.PeriodScope;
import su.onno.ui.ActionContext;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionToast;
import su.onno.ui.InputType;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The built-in period-close administration page at {@value #ROUTE}: the closes in force, "Close
 * period" and "Reopen period" buttons (restricted to {@code ADMIN} and
 * {@code onno.period-close.manage-roles}), and the recent audit trail. The UI starter registers it
 * automatically; link it from a {@code Layout} section to put it in the navigation:
 *
 * <pre>
 * spec.section("Finance").page(PeriodClosePage.ROUTE, "Period close", "lock");
 * </pre>
 *
 * Register your own {@code PeriodClosePage} bean (or subclass) to replace it.
 */
public class PeriodClosePage implements Page {

    public static final String ROUTE = "/period-close";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int AUDIT_ROWS = 20;

    private final PeriodCloseService periods;
    private final PeriodCloseAccess access;

    public PeriodClosePage(PeriodCloseService periods, PeriodCloseAccess access) {
        this.periods = periods;
        this.access = access;
    }

    @Override
    public String route() {
        return ROUTE;
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Period close");
        b.subtitle("Documents dated on or before a close date cannot be created, changed, deleted, "
                + "posted or unposted.");

        List<PeriodLock> locks = periods.locks();
        if (locks.isEmpty()) {
            b.text("No period is closed.");
        }
        for (PeriodLock lock : locks) {
            b.text(capitalize(lock.scope().describe()) + ": closed through " + lock.closedThrough()
                    + by(lock.updatedBy()) + (lock.updatedAt() == null ? "" : ", " + TIME.format(lock.updatedAt())));
        }

        String[] scopes = scopeOptions();
        String[] roles = access.manageRoleNames();
        b.actions("Manage", actions -> {
            actions.action("closePeriod").label("Close period").icon("lock").roles(roles)
                    .form(f -> {
                        f.input("scope").label("Applies to").type(InputType.SELECT).options(scopes).value("global");
                        f.input("closedThrough").label("Closed through (inclusive)").type(InputType.DATE).required();
                        f.input("reason").label("Note").type(InputType.TEXTAREA);
                    })
                    .handler(this::close);
            actions.action("reopenPeriod").label("Reopen period").icon("lock-open").roles(roles)
                    .form(f -> {
                        f.input("scope").label("Applies to").type(InputType.SELECT).options(scopes).value("global");
                        f.input("closedThrough").label("New close date (empty = fully open)").type(InputType.DATE);
                        f.input("reason").label("Reason").type(InputType.TEXTAREA).required();
                    })
                    .handler(this::reopen);
        });

        List<PeriodCloseAuditEntry> audit = periods.audit(AUDIT_ROWS);
        if (!audit.isEmpty()) {
            b.text("Recent changes");
            for (PeriodCloseAuditEntry entry : audit) {
                b.text(describe(entry));
            }
        }
    }

    private ActionResult close(ActionContext ctx) {
        try {
            LocalDate date = date(ctx.input("closedThrough"));
            if (date == null) return ActionResult.toast(ActionToast.error("Pick a close date"));
            PeriodLock lock = periods.close(PeriodScope.parse(ctx.input("scope")), date, ctx.user(),
                    blankToNull(ctx.input("reason")));
            return ActionResult.refresh(ActionToast.success(
                    capitalize(lock.scope().describe()) + " closed through " + lock.closedThrough()));
        } catch (IllegalArgumentException invalid) {
            return ActionResult.toast(ActionToast.error(invalid.getMessage()));
        }
    }

    private ActionResult reopen(ActionContext ctx) {
        try {
            PeriodScope scope = PeriodScope.parse(ctx.input("scope"));
            return periods.reopen(scope, date(ctx.input("closedThrough")), ctx.user(), ctx.input("reason"))
                    .map(lock -> ActionResult.refresh(ActionToast.success(
                            capitalize(scope.describe()) + " now closed through " + lock.closedThrough())))
                    .orElseGet(() -> ActionResult.refresh(ActionToast.success(
                            capitalize(scope.describe()) + " reopened")));
        } catch (IllegalArgumentException invalid) {
            return ActionResult.toast(ActionToast.error(invalid.getMessage()));
        }
    }

    private String[] scopeOptions() {
        List<String> options = new ArrayList<>();
        options.add(PeriodScope.global().toString());
        periods.knownContexts().forEach(context -> options.add(PeriodScope.context(context).toString()));
        periods.knownRegisters().forEach(register -> options.add(PeriodScope.register(register).toString()));
        return options.toArray(String[]::new);
    }

    private static String describe(PeriodCloseAuditEntry entry) {
        StringBuilder line = new StringBuilder(TIME.format(entry.at())).append(" — ");
        String scope = entry.scope().describe();
        if (entry.action() == PeriodCloseAction.OVERRIDE) {
            line.append("override: ").append(entry.operation() == null ? "change" : entry.operation().verb())
                    .append(' ').append(entry.documentType());
            if (entry.documentDate() != null) line.append(" dated ").append(entry.documentDate().toLocalDate());
            line.append(" (").append(scope).append(" closed through ").append(entry.closedThrough()).append(')');
        } else if (entry.action() == PeriodCloseAction.CLOSE) {
            line.append("closed ").append(scope).append(" through ").append(entry.closedThrough());
        } else {
            line.append("reopened ").append(scope)
                    .append(entry.closedThrough() == null
                            ? " fully" : ", now closed through " + entry.closedThrough())
                    .append(" (was ").append(entry.previous()).append(')');
        }
        line.append(by(entry.actor()));
        if (entry.reason() != null && !entry.reason().isBlank()) line.append(": ").append(entry.reason());
        return line.toString();
    }

    private static String by(String actor) {
        return actor == null || actor.isBlank() ? "" : " by " + actor;
    }

    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.trim();
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Not a date: " + text);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
