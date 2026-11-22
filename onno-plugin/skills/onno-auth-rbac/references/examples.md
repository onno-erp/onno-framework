# Auth And RBAC Examples

## Table Of Contents

- In-Memory Auth
- OIDC Auth
- Resource Server Auth
- Entity RBAC
- UI Roles And Permissions
- Debugging 401 vs 403

## In-Memory Auth

```yaml
onno:
  auth:
    mode: in-memory
    users:
      - username: admin
        password: "admin"
        roles: [ADMIN]
      - username: manager
        password: "manager"
        roles: [MANAGER]
    session:
      timeout: 8h
      remember-me:
        enabled: true
        key: "${REMEMBER_ME_KEY}"
```

Passwords are BCrypt-hashed at startup. Do not ship real production passwords in config.

For a public, non-sensitive iframe demo, authenticate as one in-memory user without exposing its
password and allowlist only the landing-page origin:

```yaml
server.servlet.session.cookie:
  same-site: none
  secure: true
onno.auth:
  demo.auto-login-username: manager
  embedding.frame-ancestors: ["'self'", "https://www.example.com"]
  embedding.cross-site-cookies: true
```

The cookie settings require HTTPS. Remove auto-login for any deployment containing private data.

## OIDC Auth

```yaml
onno:
  auth:
    mode: oidc
    oidc:
      provider: keycloak
      registration-id: keycloak
      roles:
        realm-roles: true
        client-roles: true
        client-id: onno-app

spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: onno-app
            client-secret: "${KEYCLOAK_CLIENT_SECRET}"
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: "https://keycloak.example.com/realms/acme"
```

OIDC keeps the session-cookie model. The login screen uses `/api/auth/me` to discover the SSO login
URL.

## Resource Server Auth

```yaml
onno:
  auth:
    mode: resource-server
    # The framework's bootstrap paths are always public; add only your own.
    additional-public-paths:
      - /api/health
      - /api/health/**
    oidc:
      provider: keycloak
      principal-claim: preferred_username
      roles:
        realm-roles: true
        client-roles: true
        client-id: onno-app
        prefix: "ROLE_"

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: "https://keycloak.example.com/realms/acme"
```

Clients send `Authorization: Bearer <token>`. CSRF is off in this mode.
`csrf-ignored-paths` is ignored. `/api/auth/login` returns `409`; token issuance/revocation belongs
to the client and identity provider.

## Entity RBAC

Roles are an enum; one `RoleGrants` bean declares every role's rights. Entity classes carry no
access annotations.

```java
public enum AppRole implements RoleKey { ADMIN, MANAGER }

@Configuration
class AccessConfiguration {
    @Bean
    RoleGrants roleGrants() {
        return RoleGrants.of(AppRole.class)
                .role(AppRole.MANAGER, r -> r
                        .read(Employee.class)                       // pickers / assignment
                        .post(Order.class).unpost(Order.class))     // post ⊃ write ⊃ read
                .role(AppRole.ADMIN, r -> r
                        .writeAll().postAll().unpostAll()
                        .permit(CorePermissions.EDIT_SETTINGS, AuthPermissions.MANAGE_USERS,
                                CorePermissions.MANAGE_PERIOD_CLOSE)
                        .fromIdp("erp-admins"));                    // an IdP role named differently
    }
}
```

`Employee` is readable by managers but writable only by `ADMIN` — because `ADMIN` is granted
`writeAll()`, not because of its name. Without this bean the app runs open (every signed-in user
may do everything); with it, anything not granted is denied.

## UI Roles And Permissions

Pages and nav items take `roles` and `permission` in the app's `main.tsx`; components check with
`useHasRole` / `useHasPermission`:

```tsx
defineApp({
  pages: [{ path: "/settings", component: SettingsPage, label: "Settings", icon: Settings, permission: "settings.edit" }],
  navigation: [{ title: "People", icon: Users, items: ["/team", { entity: "Employees", roles: ["ADMIN"] }] }],
});
```

This curates the UI, not authorization: an unreadable entity drops out of navigation by itself,
and the data and settings stay guarded on the server. Role names are typed from the `RoleKey` enum
and permission keys from the `Permission` constants (both in `model.gen.ts`); `/api/ui/model`
reports the user's `permissions` and `unrestricted` (open mode).

## Debugging 401 vs 403

- `401` means no valid authenticated identity for the request.
- `403` means authenticated but not allowed, or—only in cookie modes—CSRF token missing/invalid for
  a mutation. Resource-server mode has CSRF disabled.
- If the login screen breaks, check that nothing in front of the app (a custom security chain, a
  proxy) blocks the framework bootstrap paths (`/api/config`, `/api/branding`, `/api/auth/me`,
  `/api/auth/csrf`, `/api/ui/login`).
- If a picker is empty, check the target entity's read grant in `RoleGrants`.
- If an admin screen 403s for an SSO user, check which permission it needs and that the IdP role
  maps to a role granting it (`fromIdp(...)`); there is no ADMIN bypass.
- If MCP can read less than the UI, check the MCP user's roles and whether the client authenticates
  each request.

Resource-server smoke:

```bash
base=http://localhost:8080
token='...'
curl -i "$base/api/list/catalogs/Employees?limit=1" # 401 without bearer token
curl -fsS -H "Authorization: Bearer $token" "$base/api/auth/me" | jq -e .
curl -fsS -H "Authorization: Bearer $token" \
  "$base/api/list/catalogs/Employees?limit=1" | jq -e '.rows'
curl -fsS "$base/api/health" | jq -e .
```

The default security chain permits every non-`/api/**` request. If Actuator is exposed on the same
port, protect it with a management port/chain rather than assuming only `/actuator/health` is public.
