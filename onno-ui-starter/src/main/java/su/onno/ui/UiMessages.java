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
        d.put("login.title", "Sign in");
        d.put("login.subtitle.password", "Use your workspace credentials.");
        d.put("login.subtitle.sso", "Continue with your organization account.");
        d.put("login.sso", "Continue with {provider}");
        d.put("login.none", "No interactive login is configured for this application.");
        d.put("login.username", "Username");
        d.put("login.password", "Password");
        d.put("login.submit", "Sign in");
        d.put("login.submitting", "Signing in...");
        d.put("login.invalid", "The username or password is not correct.");

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

        // App shell / account island.
        d.put("shell.signedInAs", "Signed in as");
        d.put("shell.theme", "Theme");
        d.put("shell.signOut", "Sign out");
        d.put("shell.menu", "Menu");
        d.put("shell.more", "More");

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
