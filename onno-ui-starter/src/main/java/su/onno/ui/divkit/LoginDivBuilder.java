package su.onno.ui.divkit;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.SsoProvider;
import su.onno.ui.UiMessages;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the server-driven login screen as a DivKit card from the available {@link AuthMethods}.
 * The server decides what login looks like — which providers, whether a password form is offered —
 * so adding an IdP needs no client change.
 *
 * <p>SSO options are plain DivKit buttons (a tap is just a redirect, so an opaque
 * {@code onno://auth/sso/{id}} action suffices). Username/password capture, however, needs to read
 * the typed values on submit — which a DivKit action can't do — so it is delegated to the
 * {@code onno-login-form} custom block (a React widget), mirroring how {@code onno-form} handles
 * rich entity forms elsewhere.
 *
 * <p>When more than one kind of method is available (a password <em>and</em> at least one SSO
 * provider) the screen is split into two steps so it stays uncluttered: step one is a picker of
 * equal-width method buttons (each SSO provider plus a "Sign in with password" button), and choosing
 * password advances to step two, which shows only the credentials form behind a back link. The
 * client re-requests the card with {@code ?step=password} on selection (and back to the picker on
 * {@code onno://auth/back}). A single-kind screen (password-only or SSO-only) skips the picker and
 * renders inline as before.
 */
public final class LoginDivBuilder {

    /** The step to render when methods of more than one kind are available. */
    static final String STEP_PASSWORD = "password";

    private LoginDivBuilder() {}

    /** Back-compat overload rendering the English defaults (used by unit tests). */
    public static Map<String, Object> login(AuthMethods methods, Palette p) {
        return login(methods, p, UiMessages.defaults());
    }

    public static Map<String, Object> login(AuthMethods methods, Palette p, UiMessages msg) {
        return login(methods, p, msg, null);
    }

    /** Back-compat overload: no demo accounts. */
    public static Map<String, Object> login(AuthMethods methods, Palette p, UiMessages msg, String step) {
        return login(methods, p, msg, step, List.of());
    }

    /**
     * Renders the login card for the requested {@code step}. {@code step} is only consulted when the
     * picker is in play (both a password and SSO are offered); {@code "password"} shows the
     * credentials step, anything else (including {@code null}) shows the picker.
     *
     * <p>{@code demoAccounts} (each a {@code {label, username, password}} map) surfaces one-tap sign-in
     * shortcuts on the password step; empty renders a plain form.</p>
     */
    public static Map<String, Object> login(AuthMethods methods, Palette p, UiMessages msg, String step,
                                             List<Map<String, Object>> demoAccounts) {
        boolean hasPassword = methods.passwordEnabled();
        boolean hasSso = !methods.providers().isEmpty();
        boolean picker = hasPassword && hasSso;
        boolean passwordStep = picker && STEP_PASSWORD.equals(step);

        // What each layout shows: the picker offers method buttons (SSO + a password button); its
        // password step shows just the form behind a back link; a single-kind screen shows its one
        // kind inline.
        boolean showBack = passwordStep;
        boolean showPasswordForm = passwordStep || (hasPassword && !picker);
        boolean showPasswordButton = picker && !passwordStep;
        boolean showSsoButtons = hasSso && !passwordStep;

        List<Map<String, Object>> items = new ArrayList<>();

        if (showBack) {
            items.add(backButton(p, msg));
        }

        Map<String, Object> lock = Components.icon("lock-keyhole", p.text(), 24);
        if (lock != null) {
            items.add(lock);
        }
        items.add(Div.color(Div.text(msg.get("login.title"), 22, "bold"), p.text()));

        String subtitle = subtitleKey(showPasswordForm, picker && !passwordStep);
        Map<String, Object> sub = Div.color(Div.text(msg.get(subtitle), 13, "regular"), p.muted());
        Div.margins(sub, 0, 0, 8, 0);
        items.add(sub);

        // Password sub-form (in-memory mode): a React custom block that captures the credentials and
        // calls the auth context. DivKit can't read input values on a button tap, so this is the
        // only way to submit them.
        if (showPasswordForm) {
            // Pass any configured demo accounts to the React form as a custom prop; the widget renders
            // them as one-tap sign-in buttons above the fields. Empty → a plain password form.
            Map<String, Object> props = demoAccounts == null || demoAccounts.isEmpty()
                    ? Map.of() : Map.of("demoAccounts", demoAccounts);
            Map<String, Object> form = Div.custom("onno-login-form", props);
            Div.matchWidth(form);
            items.add(form);
        }

        // On the picker the password option leads (it's the familiar default for this app) and is the
        // primary, accented button; on a SSO-only screen the first provider is primary instead.
        if (showPasswordButton) {
            items.add(passwordButton(true, p, msg));
        }
        boolean primary = showSsoButtons && !hasPassword;
        if (showSsoButtons) {
            for (SsoProvider provider : methods.providers()) {
                items.add(ssoButton(provider, primary, p, msg));
                primary = false;
            }
        }

        if (!hasPassword && !hasSso) {
            Map<String, Object> note = Div.color(
                    Div.text(msg.get("login.none"), 13, "regular"),
                    p.muted());
            items.add(note);
        }

        Map<String, Object> card = Div.vertical(items);
        Div.matchWidth(card);
        Div.gap(card, 12);
        // Slight horizontal inset so the full-width inputs aren't flush to the card edge — their
        // focus ring (which draws a few px outside the field) would otherwise get clipped.
        Div.pad(card, 0, 6, 0, 6);
        return DivCard.of("onno-login", card);
    }

    private static String subtitleKey(boolean passwordForm, boolean picker) {
        if (picker) {
            return "login.subtitle.choose";
        }
        return passwordForm ? "login.subtitle.password" : "login.subtitle.sso";
    }

    /**
     * The "← Back" link returning the picker step. Taps {@code onno://auth/back}, which the client
     * turns into a re-request of the (default) picker card.
     */
    private static Map<String, Object> backButton(Palette p, UiMessages msg) {
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> glyph = Components.icon("arrow-left", p.muted(), 16);
        if (glyph != null) {
            parts.add(glyph);
        }
        Map<String, Object> label = Div.text(msg.get("login.back"), 13, "medium");
        Div.color(label, p.muted());
        Div.maxLines(label, 1);
        parts.add(label);

        Map<String, Object> row = Div.horizontal(parts);
        Div.wrapWidth(row);
        Div.gap(row, 6);
        Div.alignV(row, "center");
        Div.action(row, "back", "onno://auth/back");
        return row;
    }

    /** Brand-mark / glyph size on the login method buttons (px). */
    private static final int MARK = 18;

    /**
     * The "Sign in with password" method button shown on the picker. Taps {@code onno://auth/password},
     * which the client turns into a re-request of the {@code password} step (the credentials form).
     */
    private static Map<String, Object> passwordButton(boolean primary, Palette p, UiMessages msg) {
        String fg = primary ? p.page() : p.text();
        Map<String, Object> icon = Components.icon("lock-keyhole", fg, MARK);
        return methodButton(icon, msg.get("login.method.password"), "onno://auth/password", "password", primary, p);
    }

    /**
     * A full-width login button. Tapping it navigates to {@code onno://auth/sso/{id}}, which the
     * host turns into a full-page redirect to the provider's authorization URL. When the provider
     * supplies an {@link SsoProvider#iconUrl()} its brand mark is shown on the right of the label;
     * otherwise the button is label-only. The label is the provider's full
     * {@link SsoProvider#buttonLabel()} verbatim when set (so an already-localized phrase isn't
     * re-wrapped into a mixed-language {@code "Continue with …"}), otherwise the {@code login.sso}
     * framing around its name.
     */
    private static Map<String, Object> ssoButton(SsoProvider provider, boolean primary, Palette p, UiMessages msg) {
        // The button foreground (label color): light text on the primary fill, normal text on the
        // ghost variant.
        String fg = primary ? p.page() : p.text();
        // A monochrome brand mark is tinted to the app's accent (primary) color so it picks up the
        // theme — an orange primary paints the mark orange, matching the primary-filled password
        // button — while the default neutral primary keeps it near-black/near-white and legible in
        // both light and dark. On a primary-filled SSO button the mark uses the page color instead,
        // for contrast against the accent fill. A full-color mark ignores this and keeps its colors.
        String markColor = provider.monochrome() ? (primary ? p.page() : p.primary()) : fg;
        Map<String, Object> icon = providerIcon(provider, markColor);
        String label = provider.buttonLabel() != null && !provider.buttonLabel().isBlank()
                ? provider.buttonLabel()
                : msg.format("login.sso", "provider", provider.label());
        return methodButton(icon, label, ssoAction(provider), "sso", primary, p);
    }

    /**
     * A full-width method button: a centered label with an optional {@code icon} (already built by the
     * caller) on the <em>right</em> over an {@code onno://} action. An invisible spacer the mark's
     * width leads the row so the label stays optically centered. Primary buttons get the accent fill;
     * the rest are ghost (surface + hairline). Every login button is built here so they share one
     * width and shape — the picker's options, the password button, and the SSO buttons all line up.
     */
    private static Map<String, Object> methodButton(Map<String, Object> icon, String text, String action,
                                                    String logId, boolean primary, Palette p) {
        String fg = primary ? p.page() : p.text();

        Map<String, Object> label = Div.text(text, 14, "medium");
        Div.color(label, fg);
        Div.maxLines(label, 1);
        // Fill the row (so the mark is pinned to the right edge) and center the label within it.
        Div.weight(label, 1);
        Div.textAlign(label, "center");

        List<Map<String, Object>> parts = new ArrayList<>();
        if (icon != null) {
            parts.add(spacer(MARK));
        }
        parts.add(label);
        if (icon != null) {
            parts.add(icon);
        }

        Map<String, Object> btn = Div.horizontal(parts);
        Div.matchWidth(btn);
        Div.alignV(btn, "center");
        Div.gap(btn, 8);
        Div.pad(btn, 12, 14);
        Div.corner(btn, 9);
        if (primary) {
            Div.background(btn, p.primary());
        } else {
            Div.background(btn, p.surface());
            Div.stroke(btn, p.border(), 1);
        }
        Div.action(btn, logId, action);
        return btn;
    }

    /** A fixed square of empty space — balances the right-hand mark so the label stays centered. */
    private static Map<String, Object> spacer(int size) {
        Map<String, Object> s = Div.container("vertical", List.of());
        Div.width(s, size);
        Div.height(s, size);
        return s;
    }

    /**
     * The optional brand mark for an SSO button. When the provider supplies an
     * {@link SsoProvider#iconUrl()}, render it as an {@code onno-sso-icon} custom block carrying the
     * URL, the button's foreground {@code color}, a {@code size}, and the provider's
     * {@link SsoProvider#monochrome()} flag. The client shows a full-color logo as-is (keeping the
     * brand's colors), or — when {@code monochrome} — paints the SVG in {@code color} (the app's
     * accent color, resolved by the caller) so a single-color glyph picks up the theme and reads in
     * both light and dark. Returns
     * {@code null} when no icon is supplied, so the button degrades to label-only. Generic for any
     * provider — nothing here is provider-specific; the framework just renders whatever URL it is given.
     */
    private static Map<String, Object> providerIcon(SsoProvider provider, String color) {
        String url = provider.iconUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("src", url);
        props.put("color", color);
        props.put("size", MARK);
        props.put("monochrome", provider.monochrome());
        Map<String, Object> node = Div.custom("onno-sso-icon", props);
        Div.width(node, MARK);
        Div.height(node, MARK);
        return node;
    }

    /**
     * The tap action for an SSO button. Carries the provider's {@code authorizationUrl} as a
     * {@code ?to=} parameter so an additive, non-OIDC contributor (e.g. a Telegram login flow at
     * {@code /api/auth/telegram/start}) navigates to its own start endpoint rather than the OIDC
     * {@code /oauth2/authorization/{id}} convention. The id stays in the path as the action log id
     * and as the host's fallback target when no URL is carried.
     */
    private static String ssoAction(SsoProvider provider) {
        String action = "onno://auth/sso/" + provider.id();
        String url = provider.authorizationUrl();
        if (url != null && !url.isBlank()) {
            action += "?to=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        }
        return action;
    }
}
