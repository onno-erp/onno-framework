package su.onno.ui.divkit;

import su.onno.auth.spi.AuthMethods;
import su.onno.auth.spi.SsoProvider;
import su.onno.ui.UiMessages;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 */
public final class LoginDivBuilder {

    private LoginDivBuilder() {}

    /** Back-compat overload rendering the English defaults (used by unit tests). */
    public static Map<String, Object> login(AuthMethods methods, Palette p) {
        return login(methods, p, UiMessages.defaults());
    }

    public static Map<String, Object> login(AuthMethods methods, Palette p, UiMessages msg) {
        List<Map<String, Object>> items = new ArrayList<>();

        Map<String, Object> lock = Components.icon("lock-keyhole", p.text(), 24);
        if (lock != null) {
            items.add(lock);
        }
        items.add(Div.color(Div.text(msg.get("login.title"), 22, "bold"), p.text()));

        String subtitle = methods.passwordEnabled()
                ? msg.get("login.subtitle.password")
                : msg.get("login.subtitle.sso");
        Map<String, Object> sub = Div.color(Div.text(subtitle, 13, "regular"), p.muted());
        Div.margins(sub, 0, 0, 8, 0);
        items.add(sub);

        // Password sub-form (in-memory mode): a React custom block that captures the credentials and
        // calls the auth context. DivKit can't read input values on a button tap, so this is the
        // only way to submit them.
        if (methods.passwordEnabled()) {
            Map<String, Object> form = Div.custom("onno-login-form", Map.of());
            Div.matchWidth(form);
            items.add(form);
        }

        // SSO buttons. The first is primary when no password form competes with it.
        boolean primary = !methods.passwordEnabled();
        for (SsoProvider provider : methods.providers()) {
            items.add(ssoButton(provider, primary, p, msg));
            primary = false;
        }

        if (!methods.passwordEnabled() && methods.providers().isEmpty()) {
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

    /**
     * A full-width login button. Tapping it navigates to {@code onno://auth/sso/{id}}, which the
     * host turns into a full-page redirect to the provider's authorization URL.
     */
    private static Map<String, Object> ssoButton(SsoProvider provider, boolean primary, Palette p, UiMessages msg) {
        Map<String, Object> label = Div.text(msg.format("login.sso", "provider", provider.label()), 14, "medium");
        Div.color(label, primary ? p.page() : p.text());
        Div.maxLines(label, 1);

        Map<String, Object> btn = Div.horizontal(List.of(label));
        Div.matchWidth(btn);
        Div.alignH(btn, "center");
        Div.alignV(btn, "center");
        Div.pad(btn, 12, 14);
        Div.corner(btn, 9);
        if (primary) {
            Div.background(btn, p.primary());
        } else {
            Div.background(btn, p.surface());
            Div.stroke(btn, p.border(), 1);
        }
        Div.action(btn, "sso", ssoAction(provider));
        return btn;
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
