package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UiAccessService#canRead(java.util.Set, String, String)} — the pre-resolved-roles read check
 * the live SSE stream uses to filter entity-change and presence events per subscriber (#190). It must
 * mirror the {@link java.security.Principal} overload: ADMIN superuser bypass, deny-by-default for an
 * entity with no grant or a caller with no roles, and case-/separator-insensitive {@code {name}}
 * resolution. The {@code Set} overload exists because the fan-out runs off the request thread, where
 * the {@code SecurityContext} can no longer resolve the subscriber's authorities.
 */
class UiAccessServiceRoleSetTest {

    /** A catalog readable only by RENTALS, mirroring {@code @AccessControl(readRoles = {"RENTALS"})}. */
    private static CatalogDescriptor catalog(String logicalName) {
        return new CatalogDescriptor(logicalName, logicalName, logicalName.replace(" ", "_").toLowerCase(),
                Object.class, 9, false, true, "C", "Sales",
                List.of("RENTALS"), List.of(), List.of());
    }

    private UiAccessService withCatalog() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(catalog("Properties"));
        return new UiAccessService(registry);
    }

    @Test
    void grantsWhenRoleSetHoldsAReadRole() {
        UiAccessService access = withCatalog();
        assertThat(access.canRead(Set.of("RENTALS"), "catalog", "properties")).isTrue();
        // case-/separator-insensitive {name}, exactly like the Principal overload
        assertThat(access.canRead(Set.of("RENTALS"), "catalog", "Properties")).isTrue();
    }

    @Test
    void deniesWhenRoleSetLacksTheReadRole() {
        assertThat(withCatalog().canRead(Set.of("FINANCE"), "catalog", "properties")).isFalse();
    }

    @Test
    void adminRoleIsSuperuser() {
        assertThat(withCatalog().canRead(Set.of("ADMIN"), "catalog", "properties")).isTrue();
    }

    @Test
    void deniesByDefaultForNoRoles() {
        // An unauthenticated / role-less subscriber (roles resolve to an empty set) receives nothing.
        assertThat(withCatalog().canRead(Set.of(), "catalog", "properties")).isFalse();
    }

    @Test
    void deniesUnknownEntity() {
        assertThat(withCatalog().canRead(Set.of("ADMIN"), "catalog", "nonexistent")).isFalse();
    }

    @Test
    void deniesUnknownType() {
        // The presence sentinel "presence" / any unmapped kind is not a real entity type and must
        // never match — otherwise the publishPresence read check would leak.
        assertThat(withCatalog().canRead(Set.of("ADMIN"), "presence", "properties")).isFalse();
    }

    // --- canReceiveEvent: the per-subscriber SSE delivery decision (#190) ---

    @Test
    void deliversModelledKindsByPerEntityRead() {
        UiAccessService access = withCatalog();
        assertThat(access.canReceiveEvent(Set.of("RENTALS"), "catalog", "properties")).isTrue();
        assertThat(access.canReceiveEvent(Set.of("FINANCE"), "catalog", "properties")).isFalse();
    }

    @Test
    void deliversCommentEventsByTheCommentedRecordsReadGrant() {
        UiAccessService access = withCatalog();
        // A comment event is scoped to the commented record ("Properties", RENTALS-only), so it reaches
        // only a RENTALS reader — never a viewer who can't read that record.
        assertThat(access.canReceiveEvent(Set.of("RENTALS"), "comment", "properties")).isTrue();
        assertThat(access.canReceiveEvent(Set.of("FINANCE"), "comment", "properties")).isFalse();
    }

    @Test
    void failsClosedForUnknownEventTypes() {
        UiAccessService access = withCatalog();
        // A future / unknown event kind must not leak — only the ADMIN superuser receives it.
        assertThat(access.canReceiveEvent(Set.of("RENTALS"), "telemetry", "properties")).isFalse();
        assertThat(access.canReceiveEvent(Set.of("ADMIN"), "telemetry", "properties")).isTrue();
    }

    @Test
    void deliversPageEventsToAnySignedInViewer() {
        UiAccessService access = withCatalog();
        // A page route (dashboard / entity list / custom page) is not entity-scoped — any authenticated
        // viewer receives it, even one with no read grant on anything.
        assertThat(access.canReceiveEvent(Set.of("FINANCE"), "page", "/dashboard")).isTrue();
        assertThat(access.canReceiveEvent(Set.of(), "page", "/")).isTrue();
    }
}
