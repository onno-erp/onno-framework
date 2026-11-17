package su.onno.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
