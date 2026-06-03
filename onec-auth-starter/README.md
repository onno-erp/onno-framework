# onec-auth-starter

Spring Boot (Spring Security) starter that secures a oneC application's HTTP API. It contributes a
session-based, cookie-CSRF `SecurityFilterChain`, a small JSON auth API (`/api/auth/*`), and an
in-memory user store configured from properties.

The model is deliberately SPA-friendly: **everything outside `/api/**` is public** (so the SPA shell
and login screen can load), **`/api/**` requires an authenticated session**, and a handful of
bootstrap `/api/**` endpoints are whitelisted so login can happen at all.

## Enabling

The starter is **on by default** — adding it to the classpath activates the filter chain. Configure
at least one user:

```yaml
onec:
  auth:
    users:
      - username: admin
        password: "s3cret"      # plaintext here; BCrypt-hashed at startup
        roles: [ADMIN, USER]
```

Auto-configuration (`OnecAuthAutoConfiguration`) runs **before** Spring Boot's
`SecurityAutoConfiguration` / `UserDetailsServiceAutoConfiguration` and contributes a
`PasswordEncoder` (BCrypt), a `UserDetailsService` (in-memory), an `AuthenticationManager`
(`DaoAuthenticationProvider`), the `SecurityFilterChain`, and the `AuthApiController`. Every bean is
`@ConditionalOnMissingBean`, so the consuming app can override any piece. Set `onec.auth.enabled=false`
to contribute nothing and wire your own security.

### Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onec.auth.enabled` | `true` | Master switch. When `false`, no `SecurityFilterChain` is contributed and the app wires its own. |
| `onec.auth.public-paths` | see below | Ant patterns permitted without authentication, on top of the implicit "everything outside `/api/**`". |
| `onec.auth.users[*].username` | — | In-memory account username. |
| `onec.auth.users[*].password` | — | Plaintext password; BCrypt-encoded at startup. Missing username **or** password fails startup. |
| `onec.auth.users[*].roles` | `[]` | Role names (stored as `ROLE_*` authorities by Spring's `User.roles(...)`). |

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

### CSRF model

CSRF protection is **enabled** using `CookieCsrfTokenRepository.withHttpOnlyFalse()`:

- The token is delivered to the client in a **non-HttpOnly `XSRF-TOKEN` cookie**, readable by
  JavaScript.
- Mutating requests (`POST`/`PUT`/`PATCH`/`DELETE`) must echo it back in the **`X-XSRF-TOKEN`**
  header. (The request handler uses the raw token value — `CsrfTokenRequestAttributeHandler` — not
  the BREACH-protected encoded form.)
- A `CsrfCookieFilter` runs on every request and materializes the deferred token, so the
  `XSRF-TOKEN` cookie is present after the **first response** to a fresh client (e.g. `GET /api/auth/me`),
  rather than only after something downstream happens to read it.
- **`POST /api/auth/login` is exempt from CSRF** (`ignoringRequestMatchers("/api/auth/login")`) — you
  do not need a token to log in. Every other mutation under `/api/**` needs the header.

### Endpoints (`AuthApiController`, base path `/api/auth`)

| Method | Path | Auth | Body | Response |
|--------|------|------|------|----------|
| `POST` | `/api/auth/login` | public, CSRF-exempt | `{"username":"...","password":"..."}` | `200` `{"authenticated":true,"username":"...","roles":["ROLE_ADMIN",...]}`; `401` on bad credentials; `400` if username/password missing |
| `GET` | `/api/auth/me` | public | — | Current user, or `{"authenticated":false,"username":"","roles":[]}` when anonymous |
| `POST` | `/api/auth/logout` | authenticated (needs CSRF header) | — | `204 No Content`; clears the security context and invalidates the session |

`roles` are the granted authorities verbatim, so they include the `ROLE_` prefix (a `USER` role
appears as `ROLE_USER`).

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

## Gotchas

- **No default credentials.** `onec.auth.users` is empty by default — if you configure none, no one
  can log in. There is no built-in `admin`/`admin`.
- **In-memory only.** Users come from properties and live in an `InMemoryUserDetailsManager`.
  For production, define your own `UserDetailsService` bean (it wins via `@ConditionalOnMissingBean`)
  and leave `onec.auth.users` empty.
- **Passwords are plaintext in config, hashed at startup.** Put real secrets behind environment
  variables / a secrets manager, not in committed YAML.
- **Mutations need `X-XSRF-TOKEN`.** A `403` on a `POST`/`PUT`/`PATCH`/`DELETE` almost always means a
  missing or stale CSRF header — read it from the `XSRF-TOKEN` cookie. Login is the only exemption.
- **It owns the whole chain.** The bean is `@ConditionalOnMissingBean(SecurityFilterChain.class)`, so
  if your app already defines a `SecurityFilterChain`, this starter's chain is **not** applied (its
  controller and the auth beans still are). To customize selectively, prefer overriding the narrower
  beans or `onec.auth.public-paths`.
```
