/**
 * The framework's chrome strings — action buttons, confirmation dialogs, the login form,
 * empty/loading states, and client-side validation — keyed by stable ids shared with the server.
 *
 * The server is the single label source: {@code GET /api/config} returns the resolved map (English
 * defaults overlaid by {@code onno.ui.messages} overrides), and {@link MessagesProvider} overlays
 * that on this bundle. These defaults exist so the shell renders instantly (before config loads)
 * and stays readable if the fetch fails — they mirror {@code su.onno.ui.UiMessages.DEFAULTS} on the
 * server, so keep the two in sync when adding or changing a key.
 */
export const DEFAULT_MESSAGES: Record<string, string> = {
  // Login screen.
  "login.hero.title": "Business apps shaped around roles.",
  "login.hero.subtitle":
    "Sign in to see the catalogs, documents, dashboards, and forms your role is allowed to use.",
  "login.footer": "Server-driven sign-in.",
  "login.title": "Sign in",
  "login.subtitle.password": "Use your workspace credentials.",
  "login.subtitle.sso": "Continue with your organization account.",
  "login.subtitle.choose": "Choose how to sign in.",
  "login.sso": "Continue with {provider}",
  "login.method.password": "Sign in with password",
  "login.back": "Back",
  "login.none": "No interactive login is configured for this application.",
  "login.orManual": "or sign in manually",
  "login.orDemo": "or use a demo account",
  "login.username": "Username",
  "login.password": "Password",
  "login.submit": "Sign in",
  "login.submitting": "Signing in...",
  "login.invalid": "The username or password is not correct.",
  // Sign-in error banner, keyed by the ?error=<code> the server redirects back with (e.g. a failed
  // SSO/Telegram round-trip lands on /login?error=telegram). An unknown code falls back to generic.
  // Keep in sync with UiMessages.DEFAULTS.
  "login.error.telegram": "Telegram sign-in failed. Please try again.",
  "login.error.access_denied": "This account isn't authorized to use this app.",
  "login.error.session_expired": "Your session expired — please sign in again.",
  "login.error.sso": "Single sign-on failed. Please try again.",
  "login.error.generic": "Sign-in failed. Please try again.",

  // Action buttons / row-menu items.
  "action.new": "New",
  "action.add": "Add",
  "action.addRow": "Add row",
  "action.cancel": "Cancel",
  "action.save": "Write",
  "action.saving": "Saving…",
  "action.post": "Post",
  "action.repost": "Re-post",
  "action.unpost": "Unpost",
  "action.edit": "Edit",
  "action.duplicate": "Duplicate",
  "action.delete": "Delete",
  "action.open": "Open",
  "action.copyLink": "Copy link",

  // Document posting status.
  "status.posted": "Posted",
  "status.draft": "Draft",

  // Workspace tab titles. The list tab reads the entity's localized title directly (from the
  // shell's path→title map); these template the new/edit/duplicate record tabs around it.
  "tab.new": "New {entity}",
  "tab.edit": "Edit {entity}",
  "tab.duplicate": "Duplicate {entity}",

  // Navigation. The home/dashboard and settings nav items (and their open-tab chips) fall back to
  // these when no authored Page sets a title; overridable via onno.ui.messages for a localized shell.
  "nav.dashboard": "Dashboard",
  "nav.settings": "Settings",

  // Settings surface. The built-in Settings page (the @Constant editor) uses these for its
  // title/subtitle when no authored "/settings" Page overrides them; its sidebar/tab label
  // localizes via nav.settings.
  "settings.title": "Settings",
  "settings.subtitle": "App-wide configuration.",

  // Notification center (bell beside the profile + right slide-over). Mirror UiMessages.DEFAULTS.
  "notifications.title": "Notifications",
  "notifications.empty": "You're all caught up",
  "notifications.markAllRead": "Mark all read",
  "notifications.all": "All",
  "notifications.unread": "Unread",
  "notifications.typeAll": "All",
  "notifications.typeMention": "Mentions",
  "notifications.typeAssignment": "Assigned",
  "notifications.typeReply": "Replies",
  "notifications.today": "Today",
  "notifications.thisWeek": "Earlier this week",
  "notifications.older": "Older",
  "notifications.tagMention": "Mention",
  "notifications.tagAssignment": "Assignment",
  "notifications.tagReply": "Reply",

  // App shell / account island.
  "shell.signedInAs": "Signed in as",
  "shell.theme": "Theme",
  "shell.signOut": "Sign out",
  "shell.menu": "Menu",
  "shell.more": "More",

  // List grid: toolbar count, search, sort, view toggle, and the pager footer.
  "list.count": "{count} rows",
  "list.search": "Search…",
  "list.all": "All",
  "list.sortBy": "Sort by {column}",
  "list.tableView": "Table",
  "list.mapView": "Map",
  "list.customView": "Cards",
  "list.pageRange": "{from}–{to} of {total}",
  "list.pageOf": "Page {page} of {pages}",
  "list.prev": "Prev",
  "list.next": "Next",
  "list.loadingMore": "Loading more…",
  // Batch selection (⌘/Ctrl-click, Shift-click) and its context-menu operations. The selection
  // count itself reuses "list.selected" below (shared with the multi-select filter badge).
  "list.clearSelection": "Clear selection",
  "batch.delete": "Delete {n}",
  "batch.deleteConfirm": "Sure? Delete {n}",
  "batch.running": "{label} — running on {n}…",
  "batch.done": "{label}: {ok}/{n} done",
  "batch.deleted": "Deleted {ok}/{n}",
  // Row clipboard: ⌘C copies rows (TSV + app payload), ⌘V pastes them back as new records.
  "clipboard.copied": "{count} copied",
  "clipboard.pasted": "Pasted {ok}/{n}",
  "clipboard.tooMany": "Paste limited to {max} records at a time",
  // Appended to a duplicated catalog record's description (⌘V / POST …/duplicate); blank disables.
  "duplicate.copySuffix": " (copy)",
  // ⌘A in an infinite feed can only select what's loaded; hint when more rows exist server-side.
  "list.selectedPartial": "Selected {count} loaded rows — more exist, scroll to load them",
  // Closing a form with unsaved edits.
  "confirm.discard.title": "Discard changes?",
  "confirm.discard.message": "This form has unsaved changes. Close it and discard them?",
  "action.discard": "Discard",
  "list.groupBy": "Group by",
  "list.groupNone": "None",
  "list.groupByHint": "Group rows by a column",
  "list.showMore": "Show more",
  "list.groupsCapped": "Showing the first groups only — narrow with a filter to see the rest.",
  "list.granDay": "Day",
  "list.granMonth": "Month",
  "list.granYear": "Year",
  // Faceted filter bar: chip labels, date-range presets, clear-all.
  "list.filters": "Filters",
  "list.filterHint": "Filter by {label}",
  "list.clear": "Clear",
  "list.clearAll": "Clear all",
  "list.done": "Done",
  "list.selected": "{count} selected",
  "list.dateToday": "Today",
  "list.dateYesterday": "Yesterday",
  "list.dateLast7": "Last 7 days",
  "list.dateLast30": "Last 30 days",
  "list.dateThisMonth": "This month",
  "list.dateThisYear": "This year",

  // List map view: the floating count chip and the marker popup.
  "map.count": "{count} on the map",
  "map.noRecords": "No records with a location.",
  "map.showingFirst": "showing the first {shown} of {total}",
  "map.recordsHere": "{count} records here",
  "map.more": "+{count} more",

  // Pane-level load failures (content-pane.tsx), shaped by HTTP status. "forbidden" is the
  // access-denied surface a user sees opening an entity their role can't read; "notFound"
  // covers a route with no view (or a stale link); "unauthorized" a dead session.
  "error.forbidden.title": "No access",
  "error.forbidden.body":
    "Your role doesn't have permission to view this page. Ask an administrator if you think it should.",
  "error.notFound.title": "Page not found",
  "error.notFound.body": "This page doesn't exist, or it isn't available for your role.",
  "error.unauthorized.title": "Signed out",
  "error.unauthorized.body": "Your session has expired. Sign in again to continue.",
  "error.generic.title": "Something went wrong",
  "error.generic.body": "The page couldn't be loaded. This may be temporary — try again.",
  "error.retry": "Try again",
  "error.home": "Go to home",
  "error.signIn": "Sign in",

  // Empty / loading states.
  "empty.noRecords": "No records",
  "empty.noMatches": "No matches",
  "empty.noRows": "No rows yet.",
  "loading.workspace": "Loading workspace...",
  "loading.generic": "Loading…",
  "loading.searching": "Searching…",
  "loading.working": "Working…",

  // Success toasts.
  "toast.posted": "Document posted",
  "toast.unposted": "Document unposted",
  "toast.saved": "Saved",

  // Delete-confirmation dialogs.
  "confirm.delete.document.title": "Delete document?",
  "confirm.delete.document.message":
    "This document will be marked for deletion. You can't undo this from here.",
  "confirm.delete.item.title": "Delete item?",
  "confirm.delete.item.message":
    "This item will be marked for deletion. You can't undo this from here.",

  // Forms / ref pickers.
  "form.saveError": "Couldn't save: {error}",
  "form.select": "Select {name}…",
  // Shown on an open form when the record changes elsewhere (another user/tab/widget) over SSE.
  "form.staleChanged": "This record changed elsewhere.",
  "form.reload": "Reload",
  "ref.new": "New {name}",
  "relatedList.saveFirstManage": "Save this record first to manage {name}.",
  "relatedList.saveFirstView": "Save this record first to see {name}.",

  // Client-side validation messages (mirror the server's AttributeValidator).
  "validation.required": "{field} is required",
  "validation.maxLength": "{field} must be at most {n} characters",
  "validation.minLength": "{field} must be at least {n} characters",
  "validation.pattern": "{field} is not in the expected format",
  "validation.email": "{field} must be a valid email address",
  "validation.min": "{field} must be at least {n}",
  "validation.max": "{field} must be at most {n}",
};

/** A translator: resolves a key to its text, substituting {@code {placeholder}} tokens. */
export type Translate = (key: string, params?: Record<string, string | number>) => string;

/** Replace each {@code {name}} in {@code template} with {@code params[name]}; leaves unknown tokens as-is. */
export function formatMessage(
  template: string,
  params?: Record<string, string | number>
): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match
  );
}

/** Build a {@link Translate} over a resolved map, falling back to the key itself when unknown. */
export function makeTranslate(messages: Record<string, string>): Translate {
  return (key, params) => formatMessage(messages[key] ?? key, params);
}
