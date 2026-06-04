package com.onec.ui.divkit;

import com.onec.auth.spi.AuthMethods;
import com.onec.auth.spi.SsoProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the server-driven login screen as a DivKit card from the available {@link AuthMethods}.
 * The server decides what login looks like — which providers, whether a password form is offered —
 * so adding an IdP needs no client change.
 *
 * <p>SSO options are plain DivKit buttons (a tap is just a redirect, so an opaque
 * {@code onec://auth/sso/{id}} action suffices). Username/password capture, however, needs to read
 * the typed values on submit — which a DivKit action can't do — so it is delegated to the
 * {@code onec-login-form} custom block (a React widget), mirroring how {@code onec-form} handles
 * rich entity forms elsewhere.
 */
public final class LoginDivBuilder {

    private LoginDivBuilder() {}

    public static Map<String, Object> login(AuthMethods methods, Palette p) {
        List<Map<String, Object>> items = new ArrayList<>();

        Map<String, Object> lock = Components.icon("lock-keyhole", p.text(), 24);
        if (lock != null) {
            items.add(lock);
        }
        items.add(Div.color(Div.text("Sign in", 22, "bold"), p.text()));

        String subtitle = methods.passwordEnabled()
                ? "Use your workspace credentials."
                : "Continue with your organization account.";
        Map<String, Object> sub = Div.color(Div.text(subtitle, 13, "regular"), p.muted());
        Div.margins(sub, 0, 0, 8, 0);
        items.add(sub);

        // Password sub-form (in-memory mode): a React custom block that captures the credentials and
        // calls the auth context. DivKit can't read input values on a button tap, so this is the
        // only way to submit them.
        if (methods.passwordEnabled()) {
            Map<String, Object> form = Div.custom("onec-login-form", Map.of());
            Div.matchWidth(form);
            items.add(form);
        }

        // SSO buttons. The first is primary when no password form competes with it.
        boolean primary = !methods.passwordEnabled();
        for (SsoProvider provider : methods.providers()) {
            items.add(ssoButton(provider, primary, p));
            primary = false;
        }

        if (!methods.passwordEnabled() && methods.providers().isEmpty()) {
            Map<String, Object> note = Div.color(
                    Div.text("No interactive login is configured for this application.", 13, "regular"),
                    p.muted());
            items.add(note);
        }

        Map<String, Object> card = Div.vertical(items);
        Div.matchWidth(card);
        Div.gap(card, 12);
        return DivCard.of("onec-login", card);
    }

    /**
     * A full-width login button. Tapping it navigates to {@code onec://auth/sso/{id}}, which the
     * host turns into a full-page redirect to {@code /oauth2/authorization/{id}}.
     */
    private static Map<String, Object> ssoButton(SsoProvider provider, boolean primary, Palette p) {
        Map<String, Object> label = Div.text("Continue with " + provider.label(), 14, "medium");
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
        Div.action(btn, "sso", "onec://auth/sso/" + provider.id());
        return btn;
    }
}
