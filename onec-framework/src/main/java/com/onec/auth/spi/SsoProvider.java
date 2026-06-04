package com.onec.auth.spi;

/**
 * A single sign-on option offered on the login screen — one OIDC client registration.
 *
 * <p>Part of the auth↔UI seam (see {@link AuthMethodsProvider}): plain data so the UI module can
 * render an SSO button without depending on the auth module or Spring Security.
 *
 * @param id               the OIDC client registration id (e.g. {@code keycloak}, {@code zitadel})
 * @param label            human-readable button label (the client name, falling back to the id)
 * @param authorizationUrl where the browser navigates to start login
 *                         ({@code /oauth2/authorization/{id}})
 */
public record SsoProvider(String id, String label, String authorizationUrl) {
}
