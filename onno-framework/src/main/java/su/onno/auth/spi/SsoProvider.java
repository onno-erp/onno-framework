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
 * @param label            human-readable provider name (the client name, falling back to the id),
 *                         wrapped by the UI's {@code "Continue with {provider}"} framing unless
 *                         {@code buttonLabel} overrides it
 * @param authorizationUrl where the browser navigates to start login
 *                         ({@code /oauth2/authorization/{id}} for OIDC, or the connector's own start
 *                         endpoint for an additive provider)
 * @param iconUrl          optional URL of the provider's brand logo for the button, rendered on the
 *                         right of the label, so a connector can give its "Log in with X" button a real
 *                         mark. The connector that owns the provider serves the asset (e.g.
 *                         {@code /api/auth/telegram/logo.svg}); the framework only renders the URL it is
 *                         given. {@code null} renders a label-only button.
 * @param monochrome       how the {@code iconUrl} is painted: {@code false} (the default) renders the
 *                         logo as-is, keeping its brand colors (e.g. a full-color badge); {@code true}
 *                         treats it as a single-color glyph and tints it to the app's accent (primary)
 *                         color, so the mark picks up the theme — an orange primary paints it orange,
 *                         matching the primary-filled password button — and still reads in both light
 *                         and dark (the default neutral primary is near-black / near-white). On a
 *                         primary-filled SSO button the mark uses the on-primary page color instead,
 *                         for contrast against the fill. Use {@code true} for a single-color brand
 *                         glyph supplied as bare paths. Ignored when {@code iconUrl} is null.
 * @param buttonLabel      optional full, ready-to-render button label that replaces the
 *                         {@code "Continue with {provider}"} framing entirely — set this when the label
 *                         is already a complete, localized phrase (e.g. the Russian
 *                         {@code "Войти через Telegram"}) so it isn't double-wrapped into a
 *                         mixed-language string. {@code null}/blank uses the default framing.
 */
public record SsoProvider(String id, String label, String authorizationUrl, String iconUrl,
                          boolean monochrome, String buttonLabel) {

    /**
     * Convenience constructor for a provider without a button icon — equivalent to passing
     * {@code iconUrl = null}. Kept so existing callers (e.g. OIDC registrations and connectors that
     * predate icon support) compile and behave unchanged.
     */
    public SsoProvider(String id, String label, String authorizationUrl) {
        this(id, label, authorizationUrl, null, false, null);
    }

    /**
     * Convenience constructor for a provider with a full-color brand logo and the default
     * {@code "Continue with {provider}"} framing — equivalent to {@code monochrome = false} and no
     * {@code buttonLabel}. Kept so callers that adopted the earlier 4-arg form keep compiling.
     */
    public SsoProvider(String id, String label, String authorizationUrl, String iconUrl) {
        this(id, label, authorizationUrl, iconUrl, false, null);
    }
}
