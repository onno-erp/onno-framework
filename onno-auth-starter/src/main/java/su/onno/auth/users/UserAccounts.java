package su.onno.auth.users;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import su.onno.access.AccessSubject;
import su.onno.access.RoleGrants;
import su.onno.auth.AuthPermissions;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The account-management API for {@code onno.auth.mode=database}: create staff accounts, disable
 * them, change roles, reset and change passwords, and sign an account out everywhere. Inject it into
 * application code (a seeder, an onboarding action) or use the bundled {@code /api/auth/users}
 * endpoints and the {@code users} / {@code changePassword} UI widgets.
 *
 * <p>Every security-relevant change bumps the account's {@linkplain UserAccount#securityStamp()
 * security stamp}; a session or remember-me login issued under an older stamp is signed out on its
 * next request. Disabling an account or resetting its password therefore takes effect immediately.
 *
 * <p>The last enabled account holding {@link AuthPermissions#MANAGE_USERS} (through the application's
 * {@link RoleGrants}) can be neither disabled nor demoted, so an administrator cannot
 * lock everyone out of user management.
 */
public class UserAccounts {

    private static final Pattern ROLE = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*");
    private static final char[] TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final int TEMP_LENGTH = 16;

    private final UserStore store;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final RoleGrants grants;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Accounts of an application without grants ({@link RoleGrants#open()}). */
    public UserAccounts(UserStore store, PasswordEncoder encoder, PasswordPolicy policy) {
        this(store, encoder, policy, RoleGrants.open());
    }

    public UserAccounts(UserStore store, PasswordEncoder encoder, PasswordPolicy policy, RoleGrants grants) {
        this(store, encoder, policy, grants, Clock.systemUTC());
    }

    UserAccounts(UserStore store, PasswordEncoder encoder, PasswordPolicy policy, RoleGrants grants, Clock clock) {
        this.store = store;
        this.encoder = encoder;
        this.policy = policy;
        this.grants = Objects.requireNonNull(grants, "grants");
        this.clock = clock;
    }

    public PasswordPolicy policy() {
        return policy;
    }

    /** The application's grants, which decide who may manage accounts. */
    public RoleGrants grants() {
        return grants;
    }

    /** Whether a holder of {@code roles} may manage accounts. */
    public boolean canManage(Collection<String> roles) {
        return grants.resolve(roles).has(AuthPermissions.MANAGE_USERS);
    }

    /** Whether some enabled account may manage accounts ({@code users.manage}). */
    public boolean anyEnabledAccountManages() {
        return store.findAll().stream().anyMatch(this::isActiveManager);
    }

    public List<UserAccount> list() {
        return store.findAll();
    }

    public Optional<UserAccount> find(String username) {
        return store.findByUsername(username);
    }

    public Optional<UserAccount> find(UUID id) {
        return store.findById(id);
    }

    /** Whether any account exists yet. */
    public boolean isEmpty() {
        return store.count() == 0;
    }

    /**
     * Creates an account with a generated one-time password. The holder must replace it on first
     * sign-in.
     *
     * @return the account and the generated password — show it to the administrator once; it is not
     *         stored in readable form
     */
    public Created createWithTemporaryPassword(String username, Collection<String> roles) {
        String temporary = temporaryPassword();
        UserAccount account = insert(username, roles, temporary, true, false);
        return new Created(account, temporary);
    }

    /**
     * Creates an account with a chosen password.
     *
     * @param passwordChangeRequired whether the holder must replace the password on first sign-in;
     *                               pass {@code true} whenever someone other than the holder chose it
     */
    public UserAccount create(String username, Collection<String> roles, String password,
                              boolean passwordChangeRequired) {
        return insert(username, roles, password, passwordChangeRequired, true);
    }

    /**
     * First-boot seed from {@code onno.auth.users}. A seed that must be changed at first sign-in
     * skips the policy (configuration files routinely hold {@code admin}/{@code admin}, which is
     * exactly what the forced change replaces); a seed that stays valid must satisfy it.
     */
    UserAccount seed(String username, Collection<String> roles, String password, boolean changeRequired) {
        return insert(username, roles, password, changeRequired, !changeRequired);
    }

    private UserAccount insert(String username, Collection<String> roles, String password,
                               boolean changeRequired, boolean checkPolicy) {
        String name = validUsername(username);
        if (checkPolicy) {
            policy.check(name, password);
        }
        Instant now = clock.instant();
        UserAccount account = new UserAccount(UUID.randomUUID(), name, validRoles(roles), true,
                changeRequired, now, now, null, 1);
        try {
            store.insert(account, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new UserAccountException(UserAccountException.Reason.CONFLICT,
                    "An account named \"" + name + "\" already exists.");
        }
        return account;
    }

    /** Enables or disables an account. Disabling signs it out everywhere. */
    public UserAccount setEnabled(UUID id, boolean enabled) {
        return modify(id, a -> new UserAccount(a.id(), a.username(), a.roles(), enabled,
                a.passwordChangeRequired(), a.createdAt(), a.passwordChangedAt(), a.lastLoginAt(),
                a.securityStamp() + 1));
    }

    /** Replaces an account's roles. Open sessions are signed out so the new roles apply at once. */
    public UserAccount setRoles(UUID id, Collection<String> roles) {
        Set<String> valid = validRoles(roles);
        return modify(id, a -> new UserAccount(a.id(), a.username(), valid, a.enabled(),
                a.passwordChangeRequired(), a.createdAt(), a.passwordChangedAt(), a.lastLoginAt(),
                a.securityStamp() + 1));
    }

    /** Signs an account out of every session and remember-me cookie, without other changes. */
    public UserAccount signOutEverywhere(UUID id) {
        return modify(id, a -> new UserAccount(a.id(), a.username(), a.roles(), a.enabled(),
                a.passwordChangeRequired(), a.createdAt(), a.passwordChangedAt(), a.lastLoginAt(),
                a.securityStamp() + 1));
    }

    /**
     * Replaces the password with a generated one-time password that must be changed at next
     * sign-in, and signs the account out everywhere.
     *
     * @return the one-time password, to hand to the account holder
     */
    public String resetPassword(UUID id) {
        UserAccount account = require(id);
        String temporary = temporaryPassword();
        store.updatePassword(id, encoder.encode(temporary), true, clock.instant(), account.securityStamp() + 1);
        return temporary;
    }

    /**
     * The holder changes their own password. Clears a pending change requirement.
     *
     * @return the updated account (with its new security stamp)
     * @throws UserAccountException          if the current password does not match
     * @throws PasswordPolicy.InvalidPasswordException if the new one breaks the policy
     */
    public UserAccount changePassword(String username, String currentPassword, String newPassword) {
        UserStore.Credentials credentials = store.findCredentials(username)
                .filter(c -> c.account().enabled())
                .orElseThrow(() -> new UserAccountException(UserAccountException.Reason.NOT_FOUND,
                        "Account not found."));
        if (currentPassword == null || credentials.passwordHash() == null
                || !encoder.matches(currentPassword, credentials.passwordHash())) {
            throw new UserAccountException(UserAccountException.Reason.INVALID,
                    "The current password is incorrect.");
        }
        UserAccount account = credentials.account();
        policy.check(account.username(), newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new PasswordPolicy.InvalidPasswordException("Choose a password different from the current one.");
        }
        Instant now = clock.instant();
        long stamp = account.securityStamp() + 1;
        store.updatePassword(account.id(), encoder.encode(newPassword), false, now, stamp);
        return new UserAccount(account.id(), account.username(), account.roles(), account.enabled(), false,
                account.createdAt(), now, account.lastLoginAt(), stamp);
    }

    /** Records a successful interactive sign-in. */
    public void recordLogin(String username) {
        store.findByUsername(username).ifPresent(a -> store.recordLogin(a.id(), clock.instant()));
    }

    private UserAccount modify(UUID id, UnaryOperator<UserAccount> change) {
        UserAccount before = require(id);
        UserAccount after = change.apply(before);
        if (isActiveManager(before) && !isActiveManager(after)
                && store.findAll().stream().filter(this::isActiveManager).count() <= 1) {
            throw new UserAccountException(UserAccountException.Reason.CONFLICT,
                    "This is the last enabled account that may manage accounts (users.manage). "
                            + "Give another account a role holding it first.");
        }
        store.update(after);
        return after;
    }

    /** An enabled account whose roles hold {@code users.manage}. */
    private boolean isActiveManager(UserAccount account) {
        return account.enabled() && canManage(account.roles());
    }

    private UserAccount require(UUID id) {
        return store.findById(id).orElseThrow(() ->
                new UserAccountException(UserAccountException.Reason.NOT_FOUND, "Account not found."));
    }

    private String temporaryPassword() {
        char[] out = new char[Math.max(TEMP_LENGTH, Math.min(policy.minLength(), PasswordPolicy.MAX_BYTES))];
        for (int i = 0; i < out.length; i++) {
            out[i] = TEMP_ALPHABET[random.nextInt(TEMP_ALPHABET.length)];
        }
        return new String(out);
    }

    static String validUsername(String username) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new UserAccountException(UserAccountException.Reason.INVALID, "Enter a username.");
        }
        if (name.length() > 320 || name.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new UserAccountException(UserAccountException.Reason.INVALID,
                    "A username has no spaces and at most 320 characters.");
        }
        return name;
    }

    static Set<String> validRoles(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String raw : roles) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // The one normalization: "role_manager" and "ROLE_MANAGER" are stored as MANAGER.
                String role = AccessSubject.normalizeRole(raw);
                if (!ROLE.matcher(role).matches()) {
                    throw new UserAccountException(UserAccountException.Reason.INVALID,
                            "\"" + raw + "\" is not a valid role name.");
                }
                out.add(role);
            }
        }
        return out;
    }

    /**
     * A freshly created account and its one-time password.
     *
     * @param account           the account
     * @param temporaryPassword the generated password; it must be changed at first sign-in
     */
    public record Created(UserAccount account, String temporaryPassword) {
    }
}
