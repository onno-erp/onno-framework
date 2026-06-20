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
  "login.title": "Sign in",
  "login.subtitle.password": "Use your workspace credentials.",
  "login.subtitle.sso": "Continue with your organization account.",
  "login.subtitle.choose": "Choose how to sign in.",
  "login.sso": "Continue with {provider}",
  "login.method.password": "Sign in with password",
  "login.back": "Back",
  "login.none": "No interactive login is configured for this application.",
  "login.username": "Username",
  "login.password": "Password",
  "login.submit": "Sign in",
  "login.submitting": "Signing in...",
  "login.invalid": "The username or password is not correct.",

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

  // App shell / account island.
  "shell.signedInAs": "Signed in as",
  "shell.theme": "Theme",
  "shell.signOut": "Sign out",
  "shell.menu": "Menu",
  "shell.more": "More",

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
