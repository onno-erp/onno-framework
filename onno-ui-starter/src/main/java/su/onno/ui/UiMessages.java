package su.onno.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The framework's own <em>chrome</em> strings — action buttons, confirmation dialogs, the login
 * screen, empty/loading states, and client-side validation messages — as a single label map with
 * sensible English {@link #DEFAULTS defaults} a deployment can override per key.
 *
 * <p>The chrome lives in two layers (the server-side DivKit builders and the React app), so the
 * same resolved map feeds both: the {@link su.onno.ui.divkit DivKit builders} read it directly to
 * render server-side, and {@code GET /api/config} hands the whole map ({@link #asMap()}) to the web
 * client, which indexes into it by the same keys. This is the chrome counterpart to the domain
 * localization an app already has (entity {@code title}, {@code @Attribute displayName}, list-column
 * labels): those cover <em>data/field</em> text, this covers the surrounding shell.
 *
 * <p>Overrides come from {@code onno.ui.messages} (see {@link UiProperties#getMessages()}). Scope is
 * one language per deployment — there is no per-request locale negotiation; an app sets the chrome
 * language once and both layers pick it up. A {@code {name}}-style placeholder in a template is
 * filled by {@link #format(String, Object...)}.
 */
public final class UiMessages {

    /**
     * The English defaults for every chrome key. Both layers index into this same key namespace;
     * the React app ships a mirror of these as its offline fallback (see {@code lib/messages.ts}),
     * so keep the two in sync when adding or changing a key.
     */
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();

        // Login screen (DivKit card + the onno-login-form React island).
        // Left-panel hero + footer copy (React shell, login.tsx) — keep in sync with DEFAULT_MESSAGES.
        d.put("login.hero.title", "Business apps shaped around roles.");
        d.put("login.hero.subtitle",
                "Sign in to see the catalogs, documents, dashboards, and forms your role is allowed to use.");
        d.put("login.footer", "Server-driven sign-in.");
        d.put("login.title", "Sign in");
        d.put("login.subtitle.password", "Use your workspace credentials.");
        d.put("login.subtitle.sso", "Continue with your organization account.");
        d.put("login.subtitle.choose", "Choose how to sign in.");
        d.put("login.sso", "Continue with {provider}");
        d.put("login.method.password", "Sign in with password");
        d.put("login.back", "Back");
        d.put("login.none", "No interactive login is configured for this application.");
        d.put("login.orManual", "or sign in manually");
        d.put("login.orDemo", "or use a demo account");
        d.put("login.username", "Username");
        d.put("login.password", "Password");
        d.put("login.submit", "Sign in");
        d.put("login.submitting", "Signing in...");
        d.put("login.invalid", "The username or password is not correct.");
        // Sign-in error banner, keyed by the ?error=<code> the server redirects back with (e.g. a
        // failed SSO/Telegram round-trip lands on /login?error=telegram). Keep in sync with the
        // frontend DEFAULT_MESSAGES.
        d.put("login.error.telegram", "Telegram sign-in failed. Please try again.");
        d.put("login.error.access_denied", "This account isn't authorized to use this app.");
        d.put("login.error.session_expired", "Your session expired — please sign in again.");
        d.put("login.error.sso", "Single sign-on failed. Please try again.");
        d.put("login.error.generic", "Sign-in failed. Please try again.");

        // Action buttons / row-menu items.
        d.put("action.new", "New");
        d.put("action.add", "Add");
        d.put("action.addRow", "Add row");
        d.put("action.cancel", "Cancel");
        d.put("action.save", "Write");
        d.put("action.saving", "Saving…");
        d.put("action.post", "Post");
        d.put("action.repost", "Re-post");
        d.put("action.unpost", "Unpost");
        d.put("action.edit", "Edit");
        d.put("action.duplicate", "Duplicate");
        d.put("action.delete", "Delete");
        d.put("action.open", "Open");
        d.put("action.copyLink", "Copy link");

        // Document posting status.
        d.put("status.posted", "Posted");
        d.put("status.draft", "Draft");

        // Workspace tab titles. The list tab reads the entity's localized title directly (from the
        // shell's path→title map); these template the new/edit/duplicate record tabs around it.
        d.put("tab.new", "New {entity}");
        d.put("tab.edit", "Edit {entity}");
        d.put("tab.duplicate", "Duplicate {entity}");

        // Navigation. The home/dashboard nav item (and its open-tab chip) falls back to this when no
        // authored Page sets a title; an app overrides it via onno.ui.messages for a localized shell.
        // (Settings has no built-in nav entry — an app authors a Page at "/settings" and links it with
        // its own label via section(...).page("/settings", "…", "settings").)
        d.put("nav.dashboard", "Dashboard");

        // Notification center (bell beside the profile + right slide-over). Mirror messages.ts DEFAULT_MESSAGES.
        d.put("notifications.title", "Notifications");
        d.put("notifications.empty", "You're all caught up");
        d.put("notifications.markAllRead", "Mark all read");
        d.put("notifications.all", "All");
        d.put("notifications.unread", "Unread");
        d.put("notifications.typeAll", "All");
        d.put("notifications.typeMention", "Mentions");
        d.put("notifications.typeAssignment", "Assigned");
        d.put("notifications.typeReply", "Replies");
        d.put("notifications.today", "Today");
        d.put("notifications.thisWeek", "Earlier this week");
        d.put("notifications.older", "Older");
        d.put("notifications.tagMention", "Mention");
        d.put("notifications.tagAssignment", "Assignment");
        d.put("notifications.tagReply", "Reply");

        // App shell / account island.
        d.put("shell.signedInAs", "Signed in as");
        d.put("shell.theme", "Theme");
        d.put("shell.signOut", "Sign out");
        d.put("shell.menu", "Menu");
        d.put("shell.more", "More");

        // Register surface (the virtualized movements/balance lists).
        d.put("register.period", "Period");
        d.put("register.type", "Type");
        d.put("register.balanceTab", "Balance");
        d.put("register.movementsTab", "Movements");
        d.put("register.receipt", "Receipt");
        d.put("register.expense", "Expense");

        // List grid: toolbar count, search, sort, view toggle, and the pager footer.
        d.put("list.count", "{count} rows");
        d.put("list.search", "Search…");
        d.put("list.all", "All");
        d.put("list.sortBy", "Sort by {column}");
        d.put("list.tableView", "Table");
        d.put("list.mapView", "Map");
        // Fallback toggle label for a custom list renderer (ListSpec.custom) without a .label(...).
        d.put("list.customView", "Cards");
        d.put("list.pageRange", "{from}–{to} of {total}");
        d.put("list.pageOf", "Page {page} of {pages}");
        d.put("list.prev", "Prev");
        d.put("list.next", "Next");
        d.put("list.loadingMore", "Loading more…");
        // Batch selection (⌘/Ctrl-click, Shift-click) and its context-menu operations. The
        // selection count itself reuses "list.selected" below (shared with the filter badge).
        d.put("list.clearSelection", "Clear selection");
        d.put("batch.delete", "Delete {n}");
        d.put("batch.deleteConfirm", "Sure? Delete {n}");
        d.put("batch.running", "{label} — running on {n}…");
        d.put("batch.done", "{label}: {ok}/{n} done");
        d.put("batch.deleted", "Deleted {ok}/{n}");
        // Row clipboard: ⌘C copies rows (TSV + app payload), ⌘V pastes them back as new records.
        d.put("clipboard.copied", "{count} copied");
        d.put("clipboard.pasted", "Pasted {ok}/{n}");
        d.put("clipboard.tooMany", "Paste limited to {max} records at a time");
        // Appended to a duplicated catalog record's description (⌘V / POST …/duplicate); blank disables.
        d.put("duplicate.copySuffix", " (copy)");
        // ⌘A in an infinite feed can only select what's loaded; hint when more rows exist server-side.
        d.put("list.selectedPartial", "Selected {count} loaded rows — more exist, scroll to load them");
        // Closing a form with unsaved edits.
        d.put("confirm.discard.title", "Discard changes?");
        d.put("confirm.discard.message", "This form has unsaved changes. Close it and discard them?");
        d.put("action.discard", "Discard");
        d.put("list.groupBy", "Group by");
        d.put("list.groupNone", "None");
        d.put("list.groupByHint", "Group rows by a column");
        d.put("list.showMore", "Show more");
        d.put("list.groupsCapped", "Showing the first groups only — narrow with a filter to see the rest.");
        d.put("list.granDay", "Day");
        d.put("list.granMonth", "Month");
        d.put("list.granYear", "Year");
        // Faceted filter bar: chip labels, date-range presets, clear-all.
        d.put("list.filters", "Filters");
        d.put("list.filterHint", "Filter by {label}");
        d.put("list.clear", "Clear");
        d.put("list.clearAll", "Clear all");
        d.put("list.done", "Done");
        d.put("list.selected", "{count} selected");
        d.put("list.dateToday", "Today");
        d.put("list.dateYesterday", "Yesterday");
        d.put("list.dateLast7", "Last 7 days");
        d.put("list.dateLast30", "Last 30 days");
        d.put("list.dateThisMonth", "This month");
        d.put("list.dateThisYear", "This year");

        // List map view: the floating count chip and the marker popup.
        d.put("map.count", "{count} on the map");
        d.put("map.noRecords", "No records with a location.");
        d.put("map.showingFirst", "showing the first {shown} of {total}");
        d.put("map.recordsHere", "{count} records here");
        d.put("map.more", "+{count} more");

        // Pane-level load failures (the web client's content-pane error surface), shaped by HTTP
        // status. "forbidden" is the access-denied surface a user sees opening an entity their role
        // can't read; "notFound" a route with no view (or a stale link); "unauthorized" a dead
        // session. Mirror messages.ts DEFAULT_MESSAGES.
        d.put("error.forbidden.title", "No access");
        d.put("error.forbidden.body",
                "Your role doesn't have permission to view this page. Ask an administrator if you think it should.");
        d.put("error.notFound.title", "Page not found");
        d.put("error.notFound.body", "This page doesn't exist, or it isn't available for your role.");
        d.put("error.unauthorized.title", "Signed out");
        d.put("error.unauthorized.body", "Your session has expired. Sign in again to continue.");
        d.put("error.generic.title", "Something went wrong");
        d.put("error.generic.body", "The page couldn't be loaded. This may be temporary — try again.");
        d.put("error.retry", "Try again");
        d.put("error.home", "Go to home");
        d.put("error.signIn", "Sign in");

        // Empty / loading states.
        d.put("empty.noRecords", "No records");
        d.put("empty.noMatches", "No matches");
        d.put("empty.noRows", "No rows yet.");
        d.put("loading.workspace", "Loading workspace...");
        d.put("loading.generic", "Loading…");
        d.put("loading.searching", "Searching…");
        d.put("loading.working", "Working…");

        // Success toasts.
        d.put("toast.posted", "Document posted");
        d.put("toast.unposted", "Document unposted");
        d.put("toast.saved", "Saved");

        // Delete-confirmation dialogs.
        d.put("confirm.delete.document.title", "Delete document?");
        d.put("confirm.delete.document.message",
                "This document will be marked for deletion. You can't undo this from here.");
        d.put("confirm.delete.item.title", "Delete item?");
        d.put("confirm.delete.item.message",
                "This item will be marked for deletion. You can't undo this from here.");

        // Forms / ref pickers.
        d.put("form.saveError", "Couldn't save: {error}");
        d.put("form.select", "Select {name}…");
        // Shown on an open form when the record changes elsewhere (another user/tab/widget) over SSE.
        d.put("form.staleChanged", "This record changed elsewhere.");
        d.put("form.reload", "Reload");
        d.put("ref.new", "New {name}");
        d.put("relatedList.saveFirstManage", "Save this record first to manage {name}.");
        d.put("relatedList.saveFirstView", "Save this record first to see {name}.");

        // Client-side validation messages (mirrors the server's AttributeValidator).
        d.put("validation.required", "{field} is required");
        d.put("validation.maxLength", "{field} must be at most {n} characters");
        d.put("validation.minLength", "{field} must be at least {n} characters");
        d.put("validation.pattern", "{field} is not in the expected format");
        d.put("validation.email", "{field} must be a valid email address");
        d.put("validation.min", "{field} must be at least {n}");
        d.put("validation.max", "{field} must be at most {n}");

        DEFAULTS = Collections.unmodifiableMap(d);
    }

    /** Shared instance with no overrides — the back-compat default for builders/tests. */
    private static final UiMessages DEFAULT_INSTANCE = new UiMessages(Map.of());

    private final Map<String, String> resolved;

    /** Builds the resolved map: the English {@link #DEFAULTS} with the given overrides layered on top. */
    public UiMessages(Map<String, String> overrides) {
        Map<String, String> m = new LinkedHashMap<>(DEFAULTS);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    m.put(k, v);
                }
            });
        }
        this.resolved = Collections.unmodifiableMap(m);
    }

    /** The all-defaults instance — used by builder overloads and unit tests. */
    public static UiMessages defaults() {
        return DEFAULT_INSTANCE;
    }

    /** The resolved text for {@code key}, or the key itself when unknown (so a typo is visible, not blank). */
    public String get(String key) {
        return resolved.getOrDefault(key, key);
    }

    /**
     * {@link #get(String)} with {@code {placeholder}} substitution: {@code format("ref.new", "name",
     * "Customers")} → {@code "New Customers"}. Args are alternating placeholder/value pairs.
     */
    public String format(String key, Object... placeholderValues) {
        String template = get(key);
        for (int i = 0; i + 1 < placeholderValues.length; i += 2) {
            template = template.replace("{" + placeholderValues[i] + "}", String.valueOf(placeholderValues[i + 1]));
        }
        return template;
    }

    /** The full resolved map — handed to the web client via {@code GET /api/config}. */
    public Map<String, String> asMap() {
        return resolved;
    }
}
