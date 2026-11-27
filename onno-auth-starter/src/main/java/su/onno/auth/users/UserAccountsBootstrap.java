package su.onno.auth.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import su.onno.auth.AuthPermissions;
import su.onno.auth.OnnoAuthProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * First-boot seeding for {@code onno.auth.mode=database}. When the store holds no accounts yet,
 * each {@code onno.auth.users[*]} entry is copied into it once — by default flagged so the holder
 * must replace the configured password at first sign-in. After that the configuration list is
 * ignored: the database is the source of truth and accounts are managed at runtime, so a
 * {@code password: admin} in a YAML file can no longer stay valid forever.
 */
public class UserAccountsBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(UserAccountsBootstrap.class);

    private final UserAccounts accounts;
    private final OnnoAuthProperties properties;

    public UserAccountsBootstrap(UserAccounts accounts, OnnoAuthProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<OnnoAuthProperties.User> seeds = properties.getUsers();
        if (!accounts.isEmpty()) {
            if (!seeds.isEmpty()) {
                log.warn("onno.auth.users is ignored: accounts already exist in the database "
                        + "(onno.auth.mode=database). Remove the list from configuration; manage "
                        + "accounts with the users widget or /api/auth/users.");
            }
            warnWhenNobodyManagesAccounts();
            return;
        }
        if (seeds.isEmpty()) {
            log.warn("onno.auth.mode=database has no accounts, so nobody can sign in. Add one "
                    + "onno.auth.users entry to seed the first account (it gets a role holding "
                    + "users.manage), or create it from "
                    + "code with UserAccounts.");
            return;
        }
        boolean changeRequired = properties.getDatabase().isSeedPasswordChangeRequired();
        String managerRole = lockoutRole(seeds);
        boolean first = true;
        for (OnnoAuthProperties.User seed : seeds) {
            if (seed.getUsername() == null || seed.getPassword() == null) {
                throw new IllegalStateException("onno.auth.users entry is missing username or password");
            }
            List<String> roles = new ArrayList<>(seed.getRoles() == null ? List.of() : seed.getRoles());
            if (first && managerRole != null) {
                roles.add(managerRole);
                log.info("Seeding \"{}\" with role {} so that someone may manage accounts (users.manage): "
                        + "no onno.auth.users entry holds a role granting it.", seed.getUsername(), managerRole);
            }
            first = false;
            try {
                accounts.seed(seed.getUsername(), roles, seed.getPassword(), changeRequired);
            } catch (UserAccountException e) {
                if (e.reason() != UserAccountException.Reason.CONFLICT) {
                    throw new IllegalStateException("onno.auth.users entry \"" + seed.getUsername()
                            + "\" cannot be seeded: " + e.getMessage(), e);
                }
                // Another node seeded it concurrently — the account exists, which is all we need.
            } catch (PasswordPolicy.InvalidPasswordException e) {
                throw new IllegalStateException("onno.auth.users entry \"" + seed.getUsername()
                        + "\" breaks the password policy (" + e.getMessage() + "). Either choose a "
                        + "stronger password or leave onno.auth.database.seed-password-change-required=true.", e);
            }
        }
        log.warn("Seeded {} account(s) from onno.auth.users into the database{}. The list is now "
                        + "ignored — remove it from configuration.", seeds.size(),
                changeRequired ? "; each must set a new password at first sign-in" : "");
    }

    /**
     * Lockout detection for an existing store: after the grants change (for instance on upgrading
     * from the {@code ADMIN} superuser, or when a role loses {@code users.manage}) no enabled account
     * may hold {@code users.manage} any more, and nobody can manage accounts from the UI. The server
     * cannot pick someone to promote, so it says so loudly and names the fix.
     */
    private void warnWhenNobodyManagesAccounts() {
        if (accounts.grants().isOpen() || accounts.anyEnabledAccountManages()) return;
        List<String> granting = accounts.grants().rolesGranting(AuthPermissions.MANAGE_USERS);
        log.warn("No enabled account holds users.manage (AuthPermissions.MANAGE_USERS), so nobody can manage "
                + "accounts. {}", granting.isEmpty()
                ? "Grant it to a role in RoleGrants and give that role to an account."
                : "Give one of the roles " + granting + " to an account (for example with UserAccounts.setRoles "
                        + "from code), or map an existing account role to it with fromIdp(...).");
    }

    /**
     * Lockout protection: the role to add to the first seeded account when no seed would hold
     * {@code users.manage} — the first declared role granting it — or {@code null} when a seed
     * already does, or in an application without grants (where every account holds it).
     */
    private String lockoutRole(List<OnnoAuthProperties.User> seeds) {
        if (accounts.grants().isOpen()) return null;
        boolean anyManager = seeds.stream()
                .anyMatch(s -> accounts.canManage(s.getRoles() == null ? List.of() : s.getRoles()));
        if (anyManager) return null;
        List<String> granting = accounts.grants().rolesGranting(AuthPermissions.MANAGE_USERS);
        if (granting.isEmpty()) {
            log.warn("No role in RoleGrants grants users.manage (AuthPermissions.MANAGE_USERS), so no "
                    + "account will be able to manage accounts. Grant it to a role.");
            return null;
        }
        return granting.get(0);
    }
}
