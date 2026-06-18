# onno-auth-starter

Spring Boot (Spring Security) starter that secures a onno application's HTTP API. It contributes a
`SecurityFilterChain`, a small JSON auth API (`/api/auth/*`), and — depending on the selected mode —
an in-memory user store, server-side Keycloak (OIDC) login, or stateless JWT validation.

The model is deliberately SPA-friendly: **everything outside `/api/**` is public** (so the SPA shell
and login screen can load), **`/api/**` requires an authenticated session**, and a handful of
bootstrap `/api/**` endpoints are whitelisted so login can happen at all. **The authorization model is
identical across all modes** — only *how* an identity is established changes.

## Authentication modes

`onno.auth.mode` selects exactly one backend; each contributes its own `SecurityFilterChain` and the
others stay dormant.

| Mode | Value | How you log in | Session | Extra deps active |
|------|-------|----------------|---------|-------------------|
| In-memory (default) | `in-memory` | `POST /api/auth/login` against `onno.auth.users` | Cookie (`JSESSIONID`) | none |
| Keycloak OIDC login | `oidc` | Full-page redirect to Keycloak (authorization-code) | Cookie (`JSESSIONID`) | `spring-boot-starter-oauth2-client` |
| Resource server | `resource-server` | Client gets a token from Keycloak, sends `Authorization: Bearer …` | Stateless | `spring-boot-starter-oauth2-resource-server` |

Both OAuth2 starters are on the classpath but stay inert until `mode` selects them and the matching
`spring.security.oauth2.*` properties are present, so an in-memory deployment pays only the jars. The
in-memory mode below is unchanged from earlier versions; jump to [Keycloak](#keycloak-oidc--resource-server)
for the OIDC/resource-server modes.

## Enabling

The starter is **on by default** — adding it to the classpath activates the filter chain. Configure
at least one user:

```yaml
onno:
  auth:
    users:
      - username: admin
        password: "s3cret"      # plaintext here; BCrypt-hashed at startup
        roles: [ADMIN, USER]
```

Auto-configuration (`OnnoAuthAutoConfiguration`) runs **before** Spring Boot's
`SecurityAutoConfiguration` / `UserDetailsServiceAutoConfiguration` and contributes a
`PasswordEncoder` (BCrypt), a `UserDetailsService` (in-memory), an `AuthenticationManager`
(`DaoAuthenticationProvider`), the `SecurityFilterChain`, and the `AuthApiController`. Every bean is
`@ConditionalOnMissingBean`, so the consuming app can override any piece. Set `onno.auth.enabled=false`
to contribute nothing and wire your own security.

### Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onno.auth.enabled` | `true` | Master switch. When `false`, no `SecurityFilterChain` is contributed and the app wires its own. |
| `onno.auth.mode` | `in-memory` | Backend: `in-memory`, `oidc`, or `resource-server`. See [modes](#authentication-modes). |
| `onno.auth.public-paths` | see below | Ant patterns permitted without authentication, on top of the implicit "everything outside `/api/**`". |
| `onno.auth.users[*].username` | — | In-memory account username. |
| `onno.auth.users[*].password` | — | Plaintext password; BCrypt-encoded at startup. Missing username **or** password fails startup. |
| `onno.auth.users[*].roles` | `[]` | Role names (stored as `ROLE_*` authorities by Spring's `User.roles(...)`). |
| `onno.auth.session.timeout` | `8h` | Idle session timeout for the cookie modes (`in-memory`, `oidc`), applied to the servlet container and **overriding** Spring Boot's 30-minute default. Slides on every request. Ignored in `resource-server` (stateless). |
| `onno.auth.session.remember-me.enabled` | `true` | In-memory only: issue a persistent remember-me cookie on login and re-authenticate from it after the session lapses. |
| `onno.auth.session.remember-me.validity` | `14d` | How long the remember-me cookie stays valid. |
| `onno.auth.session.remember-me.key` | — | Secret signing the remember-me cookie. **Set a stable value in production** so cookies survive restarts and can't be forged; when blank a random key is generated per run (a warning is logged). |

Default `public-paths`:

```
/error
/api/theme
/api/config
/api/auth/login
/api/desktop/ready
/api/desktop/manifest
```

These are the only `/api/**` paths reachable unauthenticated: the login endpoint itself plus
non-sensitive bootstrap endpoints (theme, config, and the desktop shell's readiness probe and window
manifest). Everything else under `/api/**` is `authenticated()`; everything else (the SPA shell) is
`permitAll()`.

## How authentication works

- **Session-based, not HTTP Basic.** Form login, HTTP Basic, and the default logout are all
  explicitly disabled. You authenticate by POSTing JSON to the login endpoint; on success the server
  saves the `SecurityContext` into the HTTP session and returns the standard `JSESSIONID` cookie.
  Send that cookie on subsequent requests.
- **Session creation policy is `IF_REQUIRED`** — a session is created on successful login.
- **Unauthorized `/api/**` requests get `401` with JSON** `{"error":"unauthenticated"}` (no redirect
  to a login page).

### Session longevity & recovery

A long-lived SPA tab should not silently lose its session and dump the user back at login. Three
mechanisms keep a signed-in tab working:

- **Longer sliding session.** `onno.auth.session.timeout` (default `8h`, vs. Spring's 30 min) is
  applied to the container and slides on every request, so an actively-used or merely-parked-but-open
  tab keeps its session for a working day.
- **Remember-me (in-memory mode).** A successful password login also sets a signed remember-me cookie
  (`onno.auth.session.remember-me.*`). A request whose session has expired — idle past the timeout, or
  a reopened browser — is re-authenticated from that cookie by Spring's `RememberMeAuthenticationFilter`,
  so the user stays signed in without re-entering credentials. `POST /api/auth/logout` cancels the
  cookie so sign-out is final.
- **Silent re-auth (OIDC mode).** The SPA, on losing its session, redirects to the IdP authorization
  endpoint. If the IdP's SSO session is still alive the round-trip is invisible and the app reloads
  already signed in; only when both the app session **and** the SSO session are gone does the user see
  the IdP login. (Set a generous SSO Session Idle/Max on the realm to widen this window.)

The SPA's `AuthProvider` drives the client side: a 4-minute `/api/auth/me` heartbeat plus a 401 hook on
every data call route a lapsed session into recovery (remember-me re-auth, or the OIDC redirect)
instead of a dead panel.

### CSRF model

CSRF protection is **enabled** using `CookieCsrfTokenRepository.withHttpOnlyFalse()`:

- The token is delivered to the client in a **non-HttpOnly `XSRF-TOKEN` cookie**, readable by
  JavaScript.
- Mutating requests (`POST`/`PUT`/`PATCH`/`DELETE`) must echo it back in the **`X-XSRF-TOKEN`**
  header. (The request handler uses the raw token value — `CsrfTokenRequestAttributeHandler` — not
  the BREACH-protected encoded form.)
- **Clients that can't read the cookie** — native mobile apps in particular (iOS hides `Set-Cookie`
  from JS and there's no `document.cookie`) — fetch the token from **`GET /api/auth/csrf`** instead
  and echo it in the same header. Browser SPAs don't need it; they read the cookie directly.
- A `CsrfCookieFilter` runs on every request and materializes the deferred token, so the
  `XSRF-TOKEN` cookie is present after the **first response** to a fresh client (e.g. `GET /api/auth/me`),
  rather than only after something downstream happens to read it.
- **`POST /api/auth/login` is exempt from CSRF** (`ignoringRequestMatchers("/api/auth/login")`) — you
  do not need a token to log in. Every other mutation under `/api/**` needs the header.

### Endpoints (`AuthApiController`, base path `/api/auth`)

| Method | Path | Auth | Body | Response |
|--------|------|------|------|----------|
| `POST` | `/api/auth/login` | public, CSRF-exempt | `{"username":"...","password":"...","remember":true}` | `200` `{"authenticated":true,"username":"...","roles":["ROLE_ADMIN",...]}`; `401` on bad credentials; `400` if username/password missing. `remember` is optional (default `true`) and, when remember-me is enabled, controls whether the persistent cookie is issued |
| `GET` | `/api/auth/me` | public | — | Current user, or `{"authenticated":false,"username":"","roles":[]}` when anonymous |
| `GET` | `/api/auth/csrf` | public | — | This session's CSRF token: `{"token":"...","headerName":"X-XSRF-TOKEN","parameterName":"_csrf"}`. For clients that can't read the `XSRF-TOKEN` cookie; `token` is `null` in `resource-server` mode (CSRF off) |
| `POST` | `/api/auth/logout` | authenticated (needs CSRF header) | — | `204 No Content`; cancels the remember-me cookie, clears the security context, and invalidates the session |

`roles` are the granted authorities verbatim, so they include the `ROLE_` prefix (a `USER` role
appears as `ROLE_USER`).

Every `AuthUser` response also carries three routing hints the SPA uses to render the right
affordance without knowing the server's config:

- `mode` — `"in-memory"`, `"oidc"`, or `"resource-server"`.
- `loginUrl` — where to send the browser to sign in. Non-null **only in OIDC mode**
  (`/oauth2/authorization/{registrationId}`); the server-driven login screen renders an SSO button
  per provider instead of the password form.
- `logoutUrl` — where to send the browser to sign out. Non-null **only in OIDC mode** (the
  RP-initiated-logout path, default `/logout`); see below.

In OIDC and resource-server modes there is no password manager, so `POST /api/auth/login` returns
`409 Conflict` (with the `AuthUser` so the SPA can read `loginUrl`) instead of authenticating.

## End-to-end recipe (curl)

```bash
BASE=http://localhost:8080
JAR=cookies.txt

# 1) Hit any public endpoint to receive the XSRF-TOKEN cookie.
curl -s -c "$JAR" "$BASE/api/auth/me"
#   -> {"authenticated":false,"username":"","roles":[]}

# 2) Log in (CSRF-exempt). Stores JSESSIONID + a fresh XSRF-TOKEN in the jar.
curl -s -b "$JAR" -c "$JAR" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"s3cret"}' \
  "$BASE/api/auth/login"
#   -> {"authenticated":true,"username":"admin","roles":["ROLE_ADMIN","ROLE_USER"]}

# 3) Call a protected GET — session cookie alone is enough (no CSRF on safe methods).
curl -s -b "$JAR" "$BASE/api/some/resource"

# 4) Call a protected mutation — must send the token from the cookie as X-XSRF-TOKEN.
XSRF=$(awk '/XSRF-TOKEN/ {print $7}' "$JAR")
curl -s -b "$JAR" \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H 'Content-Type: application/json' \
  -d '{ ... }' \
  "$BASE/api/some/resource"

# 5) Log out (also a mutation, so it needs the CSRF header).
curl -s -b "$JAR" -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/api/auth/logout"
```

## OIDC (Keycloak, Zitadel, …)

The `oidc` and `resource-server` modes are plain Spring Security OAuth2 — **any** standard OIDC
provider works by pointing `spring.security.oauth2.*` at its issuer. The only provider-specific
concern is how token claims carry roles, which `onno.auth.oidc` handles via a `provider` **preset**:

- **Keycloak** — roles under `realm_access.roles` (+ optional `resource_access.<client>.roles`), each
  an object wrapping a `roles` array.
- **Zitadel** — roles under `urn:zitadel:iam:org:project:roles`, an object whose **keys** are the role
  names.
- **custom** — you spell out the claim sources yourself.

> **Breaking change (vs. the earlier Keycloak-only draft):** configuration moved from
> `onno.auth.keycloak.*` to `onno.auth.oidc.*`, and `role-prefix` is now `oidc.roles.prefix`.

### `oidc` — server-side login (recommended for the SPA)

The browser session stays cookie-based; "logging in" becomes a full-page redirect to the IdP's
authorization endpoint, and Spring exchanges the code server-side.

```yaml
onno:
  auth:
    mode: oidc
    oidc:
      provider: keycloak            # keycloak | zitadel | custom (fills the role defaults below)
      # registration-id: keycloak   # preset default; must match the registration id below
      # principal-claim: preferred_username
      logout-path: /logout                    # RP-initiated logout endpoint (see below)
      post-logout-redirect-uri: "{baseUrl}"   # where the IdP returns after sign-out
      roles:
        prefix: "ROLE_"
        # Keycloak ergonomics (ignored by other presets):
        realm-roles: true           # map realm_access.roles
        client-roles: false         # also map resource_access.<client-id>.roles
        client-id: rentals-app      # required only when client-roles: true
spring:
  security:
    oauth2:
      client:
        provider:
          keycloak:
            issuer-uri: http://localhost:8080/realms/onno
        registration:
          keycloak:
            client-id: rentals-app
            client-secret: ${KEYCLOAK_CLIENT_SECRET:}
            authorization-grant-type: authorization_code
            scope: [openid, profile, email]
```

**Zitadel** is the same, with `provider: zitadel` and a Zitadel registration (roles map from the URN
claim automatically — no `roles` config needed):

```yaml
onno:
  auth:
    mode: oidc
    oidc:
      provider: zitadel             # registration-id defaults to "zitadel"
spring:
  security:
    oauth2:
      client:
        provider:   { zitadel: { issuer-uri: https://your-instance.zitadel.cloud } }
        registration:
          zitadel: { client-id: "...", client-secret: "...", scope: [openid, profile, email] }
```

On the IdP **client**, register:

- **Valid redirect URIs**: `http://localhost:8080/login/oauth2/code/{registrationId}` (the Spring
  callback — e.g. `…/login/oauth2/code/keycloak`).
- **Valid post logout redirect URIs**: `http://localhost:8080/*` (so logout can return to the app).

Roles are read from **both** the ID token / userinfo (Zitadel) and the access token (Keycloak's
default), so mapping works regardless of the IdP and any "add to ID token" mapper. The principal name
is re-keyed to `principal-claim` (default `preferred_username`) so the framework sees a real username
rather than the `sub` UUID.

#### RP-initiated logout

Clearing only the local session would leave the IdP SSO session intact, so the next "login" would
silently re-authenticate without a prompt. In OIDC mode the starter wires **RP-initiated logout**: the
SPA navigates (full page) to `logout-path` (default `/logout`), which

1. clears the local Spring session, then
2. redirects the browser to the IdP's `end_session_endpoint` with an `id_token_hint`, and
3. the IdP ends its session and redirects back to `post-logout-redirect-uri` (`{baseUrl}` → the app
   origin), landing on the SPA shell as an anonymous user.

The SPA discovers this URL from `logoutUrl` on `/api/auth/me` and switches its logout button from a
`fetch` POST to a navigation automatically — no app code changes between modes. The endpoint is a
`GET` (so a plain navigation works, symmetric with the login redirect); the only trade-off versus the
CSRF-protected POST default is that a forced sign-out is possible, which is benign.

### `resource-server` — stateless bearer tokens

For non-browser clients (or a SPA that manages its own tokens). The client obtains a token from the
IdP directly and sends it as `Authorization: Bearer …`; the app validates the JWT signature against
the issuer's JWKS on every request. No session, no CSRF.

```yaml
onno:
  auth:
    mode: resource-server
    oidc:
      provider: keycloak            # or zitadel / custom — same role presets
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/onno
```

OAuth2 scopes are preserved as `SCOPE_*` authorities (matching Spring's default), with IdP roles added
on top. `/api/auth/login` and `/api/auth/logout` are inert here — token lifecycle is the client's
responsibility.

### `custom` — any other IdP

When neither preset fits, set `provider: custom` and list the role claims explicitly. Each source is a
claim path + a `shape` (`array` = a string array or a `{roles:[…]}` wrapper; `object-keys` = an object
keyed by role name). Claim paths resolve as a literal key first (so keys containing dots, like the
Zitadel URN, work) then by dotted-path walking.

```yaml
onno:
  auth:
    mode: oidc
    oidc:
      provider: custom
      registration-id: my-idp
      principal-claim: email
      roles:
        prefix: "ROLE_"
        sources:
          - { claim: "urn:zitadel:iam:org:project:123456:roles", shape: object-keys }
          - { claim: "realm_access", shape: array }
```

### OIDC config keys (`onno.auth.oidc.*`)

| Key | Default | Purpose |
|-----|---------|---------|
| `provider` | `keycloak` | Preset: `keycloak` \| `zitadel` \| `custom`. Fills registration id, principal claim, and role sources. |
| `registration-id` | preset | Matches `spring.security.oauth2.client.registration.*`; builds the login URL. Required for `custom`. |
| `principal-claim` | `preferred_username` | Token claim used as the authenticated principal name. |
| `logout-path` | `/logout` | OIDC RP-initiated-logout endpoint surfaced to the SPA as `logoutUrl`. |
| `post-logout-redirect-uri` | `{baseUrl}` | Where the IdP returns after sign-out; must be registered on the client. |
| `roles.prefix` | `ROLE_` | Prefix prepended to each mapped role (so `hasRole(...)` works). |
| `roles.sources[*]` | preset | Explicit `{ claim, shape }` role sources. Overrides the preset defaults. |
| `roles.realm-roles` | `true` | Keycloak preset: map `realm_access.roles`. |
| `roles.client-roles` | `false` | Keycloak preset: also map `resource_access.<client-id>.roles`. |
| `roles.client-id` | — | Keycloak preset: client whose roles are mapped; **required** when `client-roles: true`. |

### Server-driven login screen

The available methods are exposed to the UI through the `su.onno.auth.spi.AuthMethodsProvider` bean
(contract in `onno-framework`), so the UI module can build the login screen server-side without
depending on this module. In the onno UI this drives the DivKit login card (`GET /api/divkit/login`):
the server emits a password form and/or one button per SSO provider, and adding an IdP needs no client
change.

`AuthMethodsProvider` is the single source of the password flag, mode, and logout URL. To **add** a
sign-in button without replacing it — e.g. a connector that is itself an identity provider — register
an additive `su.onno.auth.spi.AuthMethodsContributor` bean; the UI appends every contributor's
`ssoProviders()` to the base list. Each `SsoProvider` carries its own `authorizationUrl`, so a button
need not follow the OIDC `/oauth2/authorization/{id}` convention (a Telegram flow can point at
`/api/auth/telegram/start`):

```java
@Bean
AuthMethodsContributor telegramLogin() {
    return () -> List.of(new SsoProvider("telegram", "Telegram", "/api/auth/telegram/start"));
}
```

## Gotchas

- **No default credentials.** `onno.auth.users` is empty by default — if you configure none, no one
  can log in. There is no built-in `admin`/`admin`.
- **In-memory only.** Users come from properties and live in an `InMemoryUserDetailsManager`.
  For production, define your own `UserDetailsService` bean (it wins via `@ConditionalOnMissingBean`)
  and leave `onno.auth.users` empty.
- **Passwords are plaintext in config, hashed at startup.** Put real secrets behind environment
  variables / a secrets manager, not in committed YAML.
- **Mutations need `X-XSRF-TOKEN`.** A `403` on a `POST`/`PUT`/`PATCH`/`DELETE` almost always means a
  missing or stale CSRF header — read it from the `XSRF-TOKEN` cookie. Login is the only exemption.
- **It owns the whole chain.** The bean is `@ConditionalOnMissingBean(SecurityFilterChain.class)`, so
  if your app already defines a `SecurityFilterChain`, this starter's chain is **not** applied (its
  controller and the auth beans still are). To customize selectively, prefer overriding the narrower
  beans or `onno.auth.public-paths`.
- **OIDC needs the `spring.security.oauth2.client.*` registration.** Setting `mode: oidc` without a
  configured client registration fails startup — the mode expects a `ClientRegistrationRepository`.
- **Register the post-logout redirect URI.** If `post-logout-redirect-uri` (the app origin) is not in
  the Keycloak client's *Valid post logout redirect URIs*, Keycloak refuses the redirect and the user
  is stranded on a Keycloak error page after sign-out.
- **`{baseUrl}` resolves from the request.** Behind a reverse proxy, make sure forwarded-header
  handling is on (`server.forward-headers-strategy=framework`) so `{baseUrl}` expands to the public
  origin and not the internal `http://localhost`.
