# Auth And RBAC Examples

## Table Of Contents

- In-Memory Auth
- OIDC Auth
- Resource Server Auth
- Entity RBAC
- UI Profile Roles
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

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: "https://keycloak.example.com/realms/acme"
```

Clients send `Authorization: Bearer <token>`. CSRF is off in this mode.

## Entity RBAC

```java
@Catalog(name = "Employees", title = "Employees", codePrefix = "E-", context = "People")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"ADMIN"})
public class Employee extends CatalogObject {
}

@Document(name = "Orders", title = "Orders", numberPrefix = "O-", context = "Sales")
@AccessControl(readRoles = {"MANAGER"}, writeRoles = {"MANAGER"})
public class Order extends DocumentObject {
}
```

`Employee` is readable by managers for pickers/assignment but writable only by admins. `Order` is
read/write for managers. `ADMIN` can always read/write even if not listed.

## UI Profile Roles

```java
@Component
public class AdminLayout implements Layout {
    @Override
    public String profile() {
        return "admin";
    }

    @Override
    public List<String> roles() {
        return List.of("ADMIN");
    }

    @Override
    public void configure(LayoutSpec spec) {
        spec.section("People").catalog(Employee.class);
    }
}
```

This controls nav/persona, not entity authorization. Do both.

## Debugging 401 vs 403

- `401` means no valid authenticated identity for the request.
- `403` means authenticated but not allowed, or CSRF token missing/invalid for mutation.
- If login screen breaks after changing `onno.auth.public-paths`, remember the property replaces the
  defaults. Repeat required defaults such as `/api/config`, `/api/auth/login`, `/api/auth/me`, and
  `/api/auth/csrf`.
- If a picker is empty, check target entity `@AccessControl(readRoles=...)`.
- If MCP can read less than the UI, check the MCP user's roles and whether the client authenticates
  each request.
