package su.onno.auth.users;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;
import su.onno.auth.AuthPermissions;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountsTest {

    enum TestRole implements RoleKey { IT, MANAGER }

    /** IT manages accounts; MANAGER does not. */
    private static final RoleGrants GRANTS = RoleGrants.of(TestRole.class)
            .role(TestRole.IT, r -> r.permit(AuthPermissions.MANAGE_USERS));

    private JdbcUserStore store;
    private UserAccounts accounts;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store = new JdbcUserStore(ds);
        accounts = new UserAccounts(store, encoder, new PasswordPolicy(8), GRANTS);
    }

    @Test
    void temporaryPasswordAccountMustChangeItAndCanSignIn() {
        UserAccounts.Created created = accounts.createWithTemporaryPassword("Alice@Example.com", List.of("MANAGER"));

        assertThat(created.temporaryPassword()).hasSizeGreaterThanOrEqualTo(16);
        assertThat(created.account().passwordChangeRequired()).isTrue();
        // Case-insensitive lookup, stored hash matches the one-time password.
        UserStore.Credentials credentials = store.findCredentials("alice@example.com").orElseThrow();
        assertThat(credentials.account().username()).isEqualTo("Alice@Example.com");
        assertThat(encoder.matches(created.temporaryPassword(), credentials.passwordHash())).isTrue();
        assertThat(credentials.account().roles()).containsExactly("MANAGER");
    }

    @Test
    void duplicateUsernameIsAConflictIgnoringCase() {
        accounts.createWithTemporaryPassword("bob", List.of());

        assertThatThrownBy(() -> accounts.createWithTemporaryPassword("BOB", List.of()))
                .isInstanceOf(UserAccountException.class)
                .extracting(e -> ((UserAccountException) e).reason())
                .isEqualTo(UserAccountException.Reason.CONFLICT);
    }

    @Test
    void changePasswordVerifiesCurrentAppliesPolicyAndClearsTheRequirement() {
        String temporary = accounts.createWithTemporaryPassword("carol", List.of()).temporaryPassword();
        long stampBefore = accounts.find("carol").orElseThrow().securityStamp();

        assertThatThrownBy(() -> accounts.changePassword("carol", "wrong", "long-enough-pass"))
                .isInstanceOf(UserAccountException.class);
        assertThatThrownBy(() -> accounts.changePassword("carol", temporary, "short"))
                .isInstanceOf(PasswordPolicy.InvalidPasswordException.class);
        assertThatThrownBy(() -> accounts.changePassword("carol", temporary, "CAROL"))
                .isInstanceOf(PasswordPolicy.InvalidPasswordException.class);

        UserAccount changed = accounts.changePassword("carol", temporary, "correct horse battery");

        assertThat(changed.passwordChangeRequired()).isFalse();
        assertThat(changed.securityStamp()).isGreaterThan(stampBefore);
        UserAccount stored = store.findById(changed.id()).orElseThrow();
        assertThat(stored.passwordChangeRequired()).isFalse();
        assertThat(stored.securityStamp()).isEqualTo(changed.securityStamp());
        assertThat(encoder.matches("correct horse battery",
                store.findCredentials("carol").orElseThrow().passwordHash())).isTrue();
    }

    @Test
    void resetIssuesANewOneTimePasswordAndMovesTheStamp() {
        UserAccount account = accounts.create("dave", List.of(), "a sturdy password", false);

        String temporary = accounts.resetPassword(account.id());

        UserStore.Credentials after = store.findCredentials("dave").orElseThrow();
        assertThat(after.account().passwordChangeRequired()).isTrue();
        assertThat(after.account().securityStamp()).isGreaterThan(account.securityStamp());
        assertThat(encoder.matches(temporary, after.passwordHash())).isTrue();
        assertThat(encoder.matches("a sturdy password", after.passwordHash())).isFalse();
    }

    @Test
    void theLastEnabledAccountManagerCannotBeDisabledOrDemoted() {
        UserAccount admin = accounts.create("root", List.of("IT"), "a sturdy password", false);

        assertThatThrownBy(() -> accounts.setEnabled(admin.id(), false))
                .isInstanceOf(UserAccountException.class)
                .hasMessageContaining("last enabled account that may manage accounts");
        assertThatThrownBy(() -> accounts.setRoles(admin.id(), Set.of("MANAGER")))
                .isInstanceOf(UserAccountException.class);

        UserAccount second = accounts.create("root2", List.of("it"), "a sturdy password", false);
        UserAccount disabled = accounts.setEnabled(admin.id(), false);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.securityStamp()).isGreaterThan(admin.securityStamp());
        assertThatThrownBy(() -> accounts.setEnabled(second.id(), false)).isInstanceOf(UserAccountException.class);
    }

    @Test
    void withoutGrantsEveryAccountManagesSoOnlyTheLastEnabledOneIsProtected() {
        UserAccounts open = new UserAccounts(store, encoder, new PasswordPolicy(8));
        UserAccount only = open.create("solo", List.of(), "a sturdy password", false);
        assertThatThrownBy(() -> open.setEnabled(only.id(), false)).isInstanceOf(UserAccountException.class);
        open.setRoles(only.id(), Set.of("ANYTHING"));
    }

    @Test
    void detectsAStoreWhereNobodyManagesAccounts() {
        // An upgraded store: accounts still hold the old ADMIN role, which the grants no longer know.
        UserAccount legacy = accounts.create("root", List.of("ADMIN"), "a sturdy password", false);
        assertThat(accounts.anyEnabledAccountManages()).isFalse();

        UserAccount it = accounts.create("ops", List.of("IT"), "a sturdy password", false);
        assertThat(accounts.anyEnabledAccountManages()).isTrue();
        accounts.setEnabled(legacy.id(), false);
        assertThat(accounts.anyEnabledAccountManages()).as("the IT account still manages").isTrue();
        assertThat(it.enabled()).isTrue();
    }

    @Test
    void canManageFollowsTheGrantsNotARoleName() {
        assertThat(accounts.canManage(List.of("ROLE_IT"))).isTrue();
        assertThat(accounts.canManage(List.of("ADMIN"))).isFalse();
    }

    @Test
    void rolesAreValidatedAndTheSpringPrefixIsStripped() {
        UserAccount account = accounts.create("erin", List.of("ROLE_MANAGER", " CLERK ", "role_auditor", "viewer"),
                "a sturdy password", false);

        assertThat(account.roles()).containsExactlyInAnyOrder("MANAGER", "CLERK", "AUDITOR", "VIEWER");
        assertThatThrownBy(() -> accounts.setRoles(account.id(), List.of("bad role")))
                .isInstanceOf(UserAccountException.class);
    }

    @Test
    void seedSkipsThePolicyOnlyWhenAChangeIsForced() {
        accounts.seed("admin", List.of("ADMIN"), "admin", true);
        assertThat(accounts.find("admin").orElseThrow().passwordChangeRequired()).isTrue();

        assertThatThrownBy(() -> accounts.seed("other", List.of(), "other", false))
                .isInstanceOf(PasswordPolicy.InvalidPasswordException.class);
    }
}
