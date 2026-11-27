package su.onno.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

class RoleGrantsTest {

    enum AppRole implements RoleKey { MANAGER, ACCOUNTANT, IT, GUEST }

    static final class Customer {}
    static final class Order {}
    static final class Invoice {}

    static final Permission EDIT_SETTINGS = Permission.of("settings.edit", "Change settings");
    static final Permission MANAGE_USERS = Permission.of("users.manage", "Manage accounts");

    private final RoleGrants grants = RoleGrants.of(AppRole.class)
            .role(AppRole.MANAGER, r -> r.read(Customer.class).post(Order.class))
            .role(AppRole.ACCOUNTANT, r -> r.readAll().unpost(Invoice.class).permit(EDIT_SETTINGS))
            .role(AppRole.IT, r -> r.permit(MANAGE_USERS).fromIdp("erp-admins"));

    @Test
    void rightsImplyTheWeakerOnes() {
        EffectiveAccess manager = grants.resolve(List.of("MANAGER"));
        assertThat(manager.can(Order.class, EntityRight.POST)).isTrue();
        assertThat(manager.can(Order.class, EntityRight.WRITE)).isTrue();
        assertThat(manager.can(Order.class, EntityRight.READ)).isTrue();
        assertThat(manager.can(Order.class, EntityRight.UNPOST)).isFalse();
        assertThat(manager.can(Customer.class, EntityRight.READ)).isTrue();
        assertThat(manager.can(Customer.class, EntityRight.WRITE)).isFalse();
        assertThat(manager.can(Invoice.class, EntityRight.READ)).isFalse();
    }

    @Test
    void wholeModelGrantsApplyToEveryEntity() {
        EffectiveAccess accountant = grants.resolve(List.of("ACCOUNTANT"));
        assertThat(accountant.can(Order.class, EntityRight.READ)).isTrue();
        assertThat(accountant.can(Order.class, EntityRight.WRITE)).isFalse();
        assertThat(accountant.can(Invoice.class, EntityRight.UNPOST)).isTrue();
    }

    @Test
    void heldRolesMerge() {
        EffectiveAccess both = grants.resolve(List.of("MANAGER", "ACCOUNTANT"));
        assertThat(both.can(Customer.class, EntityRight.READ)).isTrue();
        assertThat(both.can(Order.class, EntityRight.POST)).isTrue();
        assertThat(both.has(EDIT_SETTINGS)).isTrue();
        assertThat(both.roles()).containsExactly("MANAGER", "ACCOUNTANT");
    }

    @Test
    void permissionsAreOnlyWhatIsGranted() {
        assertThat(grants.resolve(List.of("MANAGER")).has(EDIT_SETTINGS)).isFalse();
        assertThat(grants.resolve(List.of("IT")).has(MANAGE_USERS)).isTrue();
        assertThat(grants.resolve(List.of("IT")).has(Permission.of("users.manage", null))).isTrue();
    }

    @Test
    void thereIsNoSuperuser() {
        EffectiveAccess admin = grants.resolve(List.of("ADMIN"));
        assertThat(admin.can(Order.class, EntityRight.READ)).isFalse();
        assertThat(admin.has(EDIT_SETTINGS)).isFalse();
        assertThat(admin.unrestricted()).isFalse();
        assertThat(grants.resolve(AccessSubject.user("root", Set.of("ADMIN"))).can(Order.class, EntityRight.READ)).isFalse();
    }

    @Test
    void undeclaredRoleGrantsNothing() {
        EffectiveAccess guest = grants.resolve(List.of("GUEST"));
        assertThat(guest.roles()).containsExactly("GUEST");
        assertThat(guest.can(Customer.class, EntityRight.READ)).isFalse();
    }

    @Test
    void roleNamesMatchNormalizedAndThroughIdpAliases() {
        assertThat(grants.resolve(List.of("ROLE_manager")).can(Order.class, EntityRight.POST)).isTrue();
        assertThat(grants.resolve(List.of("erp-admins")).has(MANAGE_USERS)).isTrue();
        assertThat(grants.resolve(List.of("ROLE_ERP-ADMINS")).roles()).containsExactly("IT");
    }

    @Test
    void systemSubjectIsUnrestricted() {
        EffectiveAccess system = grants.resolve(AccessSubject.system());
        assertThat(system.unrestricted()).isTrue();
        assertThat(system.can(Invoice.class, EntityRight.UNPOST)).isTrue();
    }

    @Test
    void openGrantsAllowEverything() {
        RoleGrants open = RoleGrants.open();
        assertThat(open.isOpen()).isTrue();
        assertThat(open.roleNames()).isEmpty();
        EffectiveAccess anyone = open.resolve(List.of());
        assertThat(anyone.can(Invoice.class, EntityRight.UNPOST)).isTrue();
        assertThat(anyone.has(MANAGE_USERS)).isTrue();
    }

    @Test
    void exposesDeclaredSurfaceForValidationAndCodegen() {
        assertThat(grants.roleNames()).containsExactly("MANAGER", "ACCOUNTANT", "IT", "GUEST");
        assertThat(grants.entities()).containsExactlyInAnyOrder(Customer.class, Order.class, Invoice.class);
        assertThat(grants.permissions()).containsExactlyInAnyOrder(EDIT_SETTINGS, MANAGE_USERS);
    }

    @Test
    void explicitGrantIsNeverImpliedByUnrestrictedAccess() {
        assertThat(grants.resolve(List.of("IT")).granted(MANAGE_USERS)).isTrue();
        assertThat(grants.resolve(List.of("MANAGER")).granted(MANAGE_USERS)).isFalse();
        // Everyone holds every permission in an open application, but nobody holds one explicitly.
        assertThat(RoleGrants.open().resolve(List.of()).has(MANAGE_USERS)).isTrue();
        assertThat(RoleGrants.open().resolve(List.of()).granted(MANAGE_USERS)).isFalse();
        assertThat(grants.resolve(AccessSubject.system()).granted(MANAGE_USERS)).isFalse();
    }

    @Test
    void exposesEntitiesByExactRightAndRolesByPermission() {
        assertThat(grants.entities(EntityRight.POST)).containsExactly(Order.class);
        assertThat(grants.entities(EntityRight.UNPOST)).containsExactly(Invoice.class);
        assertThat(grants.entities(EntityRight.READ)).containsExactly(Customer.class);
        assertThat(grants.rolesGranting(EDIT_SETTINGS)).containsExactly("ACCOUNTANT");
        assertThat(grants.rolesGranting(Permission.of("print.templates.manage", ""))).isEmpty();
    }

    @Test
    void recognizesDeclaredRolesAndIdpNamesOnly() {
        assertThat(grants.recognizes("ROLE_manager")).isTrue();
        assertThat(grants.recognizes("guest")).as("an enum role that grants nothing still exists").isTrue();
        assertThat(grants.recognizes("erp-admins")).as("a fromIdp name").isTrue();
        assertThat(grants.recognizes("MANGER")).isFalse();
        assertThat(RoleGrants.open().recognizes("anything")).isTrue();

        grants.requireRecognized("test list", List.of("MANAGER", "ERP-ADMINS"));
        assertThatThrownBy(() -> grants.requireRecognized("command Orders#cancel .roles(...)", List.of("MANAGER", "MANGER")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[MANGER]")
                .hasMessageContaining("command Orders#cancel")
                .hasMessageContaining("fromIdp");
        RoleGrants.open().requireRecognized("anything", List.of("MANGER"));
        assertThatThrownBy(() -> grants.requireRecognized("print form invoice", List.of("MANAGER", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[<blank>]");
    }

    static final class Receipt extends DocumentObject {}
    static final class Supplier extends CatalogObject {}

    @Test
    void wholeModelPostingReachesDocumentsOnly() {
        EffectiveAccess poster = RoleGrants.of(AppRole.class)
                .role(AppRole.MANAGER, r -> r.readAll().postAll().unpostAll())
                .resolve(List.of("MANAGER"));
        assertThat(poster.can(Receipt.class, EntityRight.POST)).isTrue();
        assertThat(poster.can(Receipt.class, EntityRight.UNPOST)).isTrue();
        assertThat(poster.can(Receipt.class, EntityRight.WRITE)).isTrue();
        assertThat(poster.can(Supplier.class, EntityRight.READ)).isTrue();
        assertThat(poster.can(Supplier.class, EntityRight.WRITE)).as("postAll() does not write catalogs").isFalse();
        assertThat(poster.can(Supplier.class, EntityRight.POST)).isFalse();
        assertThat(poster.can(null, EntityRight.POST)).as("an unknown type is no document").isFalse();
    }

    enum ClashingRole implements RoleKey { ADMIN, ROLE_ADMIN }

    @Test
    void rolesThatNormalizeAlikeAreRejected() {
        assertThatThrownBy(() -> RoleGrants.of(ClashingRole.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN and ROLE_ADMIN");
    }

    @Test
    void anAnonymousCallerHoldsNothing() {
        assertThat(EffectiveAccess.NONE.unrestricted()).isFalse();
        assertThat(EffectiveAccess.NONE.can(Order.class, EntityRight.READ)).isFalse();
        assertThat(EffectiveAccess.NONE.has(MANAGE_USERS)).isFalse();
        assertThat(EffectiveAccess.NONE.granted(MANAGE_USERS)).isFalse();
        assertThat(EffectiveAccess.NONE.roles()).isEmpty();
    }

    @Test
    void aRoleIsDeclaredOnce() {
        assertThatThrownBy(() -> grants.role(AppRole.MANAGER, r -> r.readAll()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANAGER");
    }

    @Test
    void anIdpNameMapsToOneRole() {
        assertThatThrownBy(() -> RoleGrants.of(AppRole.class)
                .role(AppRole.IT, r -> r.fromIdp("staff"))
                .role(AppRole.MANAGER, r -> r.fromIdp("staff")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STAFF");
        assertThatThrownBy(() -> RoleGrants.of(AppRole.class).role(AppRole.IT, r -> r.fromIdp("manager")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grantsAreImmutable() {
        RoleGrants base = RoleGrants.of(AppRole.class);
        base.role(AppRole.MANAGER, r -> r.readAll());
        assertThat(base.resolve(List.of("MANAGER")).can(Order.class, EntityRight.READ)).isFalse();
    }

    @Test
    void permissionKeysAreValidated() {
        assertThatThrownBy(() -> Permission.of("ADMIN", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Permission.of("settings", "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Permission.of("periodClose.manage", "").key()).isEqualTo("periodClose.manage");
    }
}
