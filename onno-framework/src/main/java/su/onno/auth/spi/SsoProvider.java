package su.onno.auth.spi;

/**
 * A single sign-on option offered on the login screen — typically one OIDC client registration, or an
 * additive non-OIDC identity provider contributed by a connector (see {@link AuthMethodsContributor}).
 *
 * <p>Part of the auth↔UI seam (see {@link AuthMethodsProvider}): plain data so the UI module can
 * render an SSO button without depending on the auth module or Spring Security.
 *
 * @param id               the OIDC client registration id (e.g. {@code keycloak}, {@code zitadel}),
 *                         or the connector's provider id (e.g. {@code telegram})
 * @param label            human-readable button label (the client name, falling back to the id)
 * @param authorizationUrl where the browser navigates to start login
 *                         ({@code /oauth2/authorization/{id}} for OIDC, or the connector's own start
 *                         endpoint for an additive provider)
 * @param iconUrl          optional URL of a monochrome logo for the button — rendered to the left of
 *                         the label and tinted to the button's text color, so a connector can give its
 *                         "Log in with X" button a logo. The connector that owns the provider serves
 *                         the asset (e.g. {@code /api/auth/telegram/logo.svg}); the framework only
 *                         renders the URL it is given. {@code null} renders a label-only button.
 */
public record SsoProvider(String id, String label, String authorizationUrl, String iconUrl) {

    /**
     * Convenience constructor for a provider without a button icon — equivalent to passing
     * {@code iconUrl = null}. Kept so existing callers (e.g. OIDC registrations and connectors that
     * predate icon support) compile and behave unchanged.
     */
    public SsoProvider(String id, String label, String authorizationUrl) {
        this(id, label, authorizationUrl, null);
    }
}
