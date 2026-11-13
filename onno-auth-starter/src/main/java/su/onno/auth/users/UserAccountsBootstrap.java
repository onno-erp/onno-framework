package su.onno.auth.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import su.onno.auth.OnnoAuthProperties;

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
            return;
        }
        if (seeds.isEmpty()) {
            log.warn("onno.auth.mode=database has no accounts, so nobody can sign in. Add one "
                    + "onno.auth.users entry (an ADMIN) to seed the first account, or create it from "
                    + "code with UserAccounts.");
            return;
        }
        boolean changeRequired = properties.getDatabase().isSeedPasswordChangeRequired();
        for (OnnoAuthProperties.User seed : seeds) {
            if (seed.getUsername() == null || seed.getPassword() == null) {
                throw new IllegalStateException("onno.auth.users entry is missing username or password");
            }
            try {
                accounts.seed(seed.getUsername(), seed.getRoles(), seed.getPassword(), changeRequired);
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
}
