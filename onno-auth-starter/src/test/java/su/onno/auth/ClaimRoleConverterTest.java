package su.onno.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimRoleConverterTest {

    private static Map<String, Object> keycloakClaims() {
        return Map.of(
                "realm_access", Map.of("roles", List.of("admin", "rentals")),
                "resource_access", Map.of(
                        "rentals-app", Map.of("roles", List.of("billing")),
                        "account", Map.of("roles", List.of("view-profile"))));
    }

    private static OnnoAuthProperties.Oidc keycloak() {
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.KEYCLOAK);
        return oidc;
    }

    private static ClaimRoleConverter converter(OnnoAuthProperties.Oidc oidc) {
        return new ClaimRoleConverter(oidc.resolved());
    }

    // --- Keycloak preset: ARRAY shape (realm_access / resource_access wrappers) ----------

    @Test
    void mapsRealmRolesWithRolePrefixByDefault() {
        assertThat(authorities(converter(keycloak()).convert(keycloakClaims())))
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_rentals");
    }

    @Test
    void mapsClientRolesOnlyForTheConfiguredClient() {
        OnnoAuthProperties.Oidc oidc = keycloak();
        oidc.getRoles().setRealmRoles(false);
        oidc.getRoles().setClientRoles(true);
        oidc.getRoles().setClientId("rentals-app");

        assertThat(authorities(converter(oidc).convert(keycloakClaims())))
                .containsExactly("ROLE_billing");
    }

    @Test
    void mergesRealmAndClientRolesWhenBothEnabled() {
        OnnoAuthProperties.Oidc oidc = keycloak();
        oidc.getRoles().setClientRoles(true);
        oidc.getRoles().setClientId("rentals-app");

        assertThat(authorities(converter(oidc).convert(keycloakClaims())))
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_rentals", "ROLE_billing");
    }

    @Test
    void honoursCustomRolePrefix() {
        OnnoAuthProperties.Oidc oidc = keycloak();
        oidc.getRoles().setPrefix("");

        assertThat(authorities(converter(oidc).convert(keycloakClaims())))
                .containsExactlyInAnyOrder("admin", "rentals");
    }

    @Test
    void failsFastWhenClientRolesEnabledWithoutClientId() {
        OnnoAuthProperties.Oidc oidc = keycloak();
        oidc.getRoles().setClientRoles(true);

        assertThatThrownBy(oidc::resolved)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");
    }

    @Test
    void toleratesMissingClaims() {
        OnnoAuthProperties.Oidc oidc = keycloak();
        oidc.getRoles().setClientRoles(true);
        oidc.getRoles().setClientId("rentals-app");

        assertThat(converter(oidc).convert(Map.of())).isEmpty();
        assertThat(converter(oidc).convert(null)).isEmpty();
    }

    // --- Zitadel: OBJECT_KEYS shape, claim key contains dots -----------------------------

    @Test
    void zitadelMapsObjectKeyRoles() {
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.ZITADEL);
        Map<String, Object> claims = Map.of(
                "urn:zitadel:iam:org:project:roles", Map.of(
                        "admin", Map.of("orgId", "acme.example.com"),
                        "user", Map.of("orgId", "acme.example.com")));

        assertThat(authorities(converter(oidc).convert(claims)))
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_user");
    }

    @Test
    void claimKeyContainingDotsResolvesAsLiteralKeyNotDottedPath() {
        // The project-scoped URN is a single top-level claim key that contains dots; it must be
        // read literally, not split into nested-map segments.
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.CUSTOM);
        oidc.setRegistrationId("zitadel");
        oidc.getRoles().getSources().add(new OnnoAuthProperties.RoleSource(
                "urn:zitadel:iam:org:project:123456:roles", OnnoAuthProperties.Shape.OBJECT_KEYS));
        Map<String, Object> claims = Map.of(
                "urn:zitadel:iam:org:project:123456:roles", Map.of("ceo", Map.of()));

        assertThat(authorities(converter(oidc).convert(claims))).containsExactly("ROLE_ceo");
    }

    @Test
    void arrayShapeAlsoAcceptsADirectStringArrayClaim() {
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.CUSTOM);
        oidc.setRegistrationId("my-idp");
        oidc.getRoles().getSources().add(new OnnoAuthProperties.RoleSource(
                "roles", OnnoAuthProperties.Shape.ARRAY));

        assertThat(authorities(converter(oidc).convert(Map.of("roles", List.of("a", "b")))))
                .containsExactlyInAnyOrder("ROLE_a", "ROLE_b");
    }

    // --- preset resolution ---------------------------------------------------------------

    @Test
    void keycloakPresetFillsDefaults() {
        OnnoAuthProperties.ResolvedOidc r = keycloak().resolved();
        assertThat(r.registrationId()).isEqualTo("keycloak");
        assertThat(r.principalClaim()).isEqualTo("preferred_username");
        assertThat(r.roleSources()).singleElement().satisfies(s -> {
            assertThat(s.getClaim()).isEqualTo("realm_access");
            assertThat(s.getShape()).isEqualTo(OnnoAuthProperties.Shape.ARRAY);
        });
    }

    @Test
    void zitadelPresetFillsDefaults() {
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.ZITADEL);
        OnnoAuthProperties.ResolvedOidc r = oidc.resolved();
        assertThat(r.registrationId()).isEqualTo("zitadel");
        assertThat(r.roleSources()).singleElement().satisfies(s -> {
            assertThat(s.getClaim()).isEqualTo("urn:zitadel:iam:org:project:roles");
            assertThat(s.getShape()).isEqualTo(OnnoAuthProperties.Shape.OBJECT_KEYS);
        });
    }

    @Test
    void customProviderWithoutRegistrationIdFailsFast() {
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.CUSTOM);

        assertThatThrownBy(oidc::resolved)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registration-id");
    }

    @Test
    void customProviderWithoutSourcesYieldsNoAuthorities() {
        OnnoAuthProperties.Oidc oidc = new OnnoAuthProperties.Oidc();
        oidc.setProvider(OnnoAuthProperties.Provider.CUSTOM);
        oidc.setRegistrationId("my-idp");

        assertThat(converter(oidc).convert(keycloakClaims())).isEmpty();
    }

    private static List<String> authorities(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
