package com.onec.auth.spi;

import java.util.List;

/**
 * Describes how a user may authenticate, so the login screen can be composed server-side rather than
 * hardcoded in the client. Produced by an {@link AuthMethodsProvider} and consumed by the UI module
 * to build a server-driven (DivKit) login surface.
 *
 * <p>Deliberately plain data with no Spring / Spring Security types, because it crosses the
 * {@code onec-framework} seam shared by the auth and UI modules.
 *
 * @param passwordEnabled  whether interactive username/password login is available (the in-memory mode)
 * @param magicLinkEnabled whether passwordless email "magic link" login is offered (in-memory mode only;
 *                         requires {@code onec.auth.magic-link.enabled} and a configured mail provider)
 * @param providers        the SSO options to offer, possibly empty
 * @param logoutUrl        where the client navigates to log out; non-null only when logout requires a
 *                         server round-trip (OIDC RP-initiated logout), null otherwise
 * @param mode             the active backend: {@code in-memory}, {@code oidc}, or {@code resource-server}
 */
public record AuthMethods(boolean passwordEnabled, boolean magicLinkEnabled, List<SsoProvider> providers,
                          String logoutUrl, String mode) {

    public AuthMethods {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * Backward-compatible constructor for callers that don't offer magic-link login. Equivalent to
     * passing {@code magicLinkEnabled = false}; keeps existing SSO contributors and the password/OIDC
     * modes source-compatible after the magic-link flag was added.
     */
    public AuthMethods(boolean passwordEnabled, List<SsoProvider> providers, String logoutUrl, String mode) {
        this(passwordEnabled, false, providers, logoutUrl, mode);
    }
}
