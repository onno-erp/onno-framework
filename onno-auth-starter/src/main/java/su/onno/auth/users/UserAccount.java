package su.onno.auth.users;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One sign-in account held by a {@link UserStore} in {@code onno.auth.mode=database}. It never
 * carries the password hash — that stays inside the store and is reached only through
 * {@link UserStore#findCredentials(String)}.
 *
 * <p>The account is a login, not a business record. Link it to an employee/customer catalog record
 * the usual way, with {@code Layout.identity(Employee.class, "email")}: the identity catalog
 * attribute is matched against {@link #username()}.
 *
 * @param id                     stable account id
 * @param username               the login name, as entered when the account was created
 * @param roles                  granted role names, without the {@code ROLE_} prefix
 * @param enabled                false blocks sign-in and ends existing sessions
 * @param passwordChangeRequired the next sign-in must set a new password before using the app
 * @param createdAt              when the account was created
 * @param passwordChangedAt      when the password was last set (null if it never was)
 * @param lastLoginAt            last successful interactive sign-in (null if never)
 * @param securityStamp          incremented on every security-relevant change; sessions issued
 *                               under an older stamp are signed out on their next request
 */
public record UserAccount(UUID id,
                          String username,
                          Set<String> roles,
                          boolean enabled,
                          boolean passwordChangeRequired,
                          Instant createdAt,
                          Instant passwordChangedAt,
                          Instant lastLoginAt,
                          long securityStamp) {

    public UserAccount {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
