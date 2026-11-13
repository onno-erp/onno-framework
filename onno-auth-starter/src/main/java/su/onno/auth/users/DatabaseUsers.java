package su.onno.auth.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The account service of {@code onno.auth.mode=database}: create and disable users, set roles and
 * passwords, and run the password-reset and one-time-login-code flows. The admin REST API
 * ({@code /api/auth/users}) and the public reset/code endpoints are thin wrappers over this bean;
 * inject it to drive the same operations from application code (e.g. create an account when an
 * {@code Employee} is hired).
 *
 * <p>Methods that take an untrusted login ({@link #requestCode}, {@link #resetPassword},
 * {@link #redeemLoginCode}) never reveal whether the account exists: an unknown, disabled or
 * rate-limited login behaves exactly like a wrong code.
 */
public class DatabaseUsers {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUsers.class);

    /**
     * Settings of the code flows and password policy; bound from {@code onno.auth.database.*}.
     *
     * @param passwordMinLength   shortest accepted password
     * @param passwordResetValidity lifetime of a reset token
     * @param loginCodeValidity   lifetime of a one-time login code
     * @param loginCodeLength     digits in a one-time login code
     * @param maxAttempts         redemption attempts a single secret tolerates
     * @param maxRequests         secrets one login may request per purpose within {@code requestWindow}
     * @param requestWindow       the rate-limit window
     * @param statusCacheTtl      how long an "is this account still enabled" answer is reused
     */
    public record Settings(int passwordMinLength, Duration passwordResetValidity, Duration loginCodeValidity,
                           int loginCodeLength, int maxAttempts, int maxRequests, Duration requestWindow,
                           Duration statusCacheTtl) {

        public static Settings defaults() {
            return new Settings(8, Duration.ofMinutes(30), Duration.ofMinutes(10), 6, 5, 3,
                    Duration.ofMinutes(15), Duration.ofSeconds(30));
        }
    }

    private record Status(boolean active, long checkedAtNanos) {
    }

    private final UserAccountStore store;
    private final AuthCodeStore codes;
    private final PasswordEncoder passwordEncoder;
    private final AuthCodeSender sender;
    private final Executor senderExecutor;
    private final Settings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Status> statusCache = new ConcurrentHashMap<>();

    public DatabaseUsers(UserAccountStore store, AuthCodeStore codes, PasswordEncoder passwordEncoder,
                         AuthCodeSender sender, Executor senderExecutor, Settings settings, Clock clock) {
        this.store = store;
        this.codes = codes;
        this.passwordEncoder = passwordEncoder;
        this.sender = sender;
        this.senderExecutor = senderExecutor;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ administration

    /**
     * Creates an enabled account.
     *
     * @param password the initial password, or {@code null} to create the account without one — the
     *                 user then sets it through a password reset
     * @throws DuplicateLoginException  when the login is taken
     * @throws IllegalArgumentException when the login, a role or the password is invalid
     */
    public UserAccount create(String login, String password, Collection<String> roles) {
        if (login == null || login.isBlank() || login.trim().length() > 256) {
            throw new IllegalArgumentException("A login of 1-256 characters is required");
        }
        String hash = password == null ? null : encode(password);
        UserAccount account = new UserAccount(UUID.randomUUID(), login.trim(), hash, true,
                normalizeRoles(roles), clock.instant());
        store.insert(account);
        return account;
    }

    public Optional<UserAccount> find(String login) {
        return store.findByLogin(login);
    }

    public Optional<UserAccount> findById(UUID id) {
        return store.findById(id);
    }

    public List<UserAccount> list() {
        return store.findAll();
    }

    /** Replaces the account's roles. Takes effect at the user's next sign-in. */
    public UserAccount setRoles(UUID id, Collection<String> roles) {
        UserAccount updated = require(id).withRoles(normalizeRoles(roles));
        store.update(updated);
        return updated;
    }

    /**
     * Enables or disables the account. Disabling blocks sign-in immediately, voids outstanding
     * codes, and ends the user's live sessions within the status-cache interval.
     */
    public UserAccount setEnabled(UUID id, boolean enabled) {
        UserAccount updated = require(id).withEnabled(enabled);
        store.update(updated);
        String key = JdbcUserAccountStore.key(updated.login());
        statusCache.remove(key);
        if (!enabled) {
            codes.invalidateAll(key, clock.instant());
        }
        return updated;
    }

    /** Sets a new password (admin action) and voids outstanding codes. */
    public UserAccount setPassword(UUID id, String password) {
        return storePassword(require(id), password);
    }

    /**
     * Changes the password of a signed-in user who proves knowledge of the current one.
     *
     * @return false when the current password is wrong (or the account is gone/disabled)
     * @throws IllegalArgumentException when the new password violates the policy
     */
    public boolean changePassword(String login, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        Optional<UserAccount> account = store.findByLogin(login).filter(UserAccount::enabled);
        if (account.isEmpty() || !account.get().hasPassword() || currentPassword == null
                || !passwordEncoder.matches(currentPassword, account.get().passwordHash())) {
            return false;
        }
        storePassword(account.get(), newPassword);
        return true;
    }

    // ------------------------------------------------------------------ one-time secrets

    /**
     * Issues a secret and hands it to the {@link AuthCodeSender} — if the login belongs to an enabled
     * account and its rate limit allows. Returns nothing on purpose: the caller must answer the same
     * way in every case.
     */
    public void requestCode(String login, AuthCodePurpose purpose) {
        Optional<UserAccount> account = login == null ? Optional.empty()
                : store.findByLogin(login).filter(UserAccount::enabled);
        if (account.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        boolean reset = purpose == AuthCodePurpose.PASSWORD_RESET;
        Duration validity = reset ? settings.passwordResetValidity() : settings.loginCodeValidity();
        String code = reset ? randomToken() : randomDigits(settings.loginCodeLength());
        boolean issued = codes.issue(JdbcUserAccountStore.key(account.get().login()), purpose, code, now,
                validity, settings.maxRequests(), settings.requestWindow());
        if (!issued) {
            log.debug("{} request for '{}' was rate-limited", purpose, account.get().login());
            return;
        }
        AuthCodeMessage message = new AuthCodeMessage(account.get().login(), purpose, code, now.plus(validity));
        senderExecutor.execute(() -> {
            try {
                sender.send(message);
            } catch (RuntimeException ex) {
                log.warn("AuthCodeSender failed to deliver a {} secret for '{}'",
                        purpose, message.login(), ex);
            }
        });
    }

    /**
     * Sets a new password in exchange for a valid reset token. The token is single-use.
     *
     * @return false when the token is wrong, expired, used, or the account is unknown/disabled
     * @throws IllegalArgumentException when the new password violates the policy (checked before the
     *                                  token, so this never reveals whether the token was valid)
     */
    public boolean resetPassword(String login, String code, String newPassword) {
        validatePassword(newPassword);
        Optional<UserAccount> account = redeem(login, AuthCodePurpose.PASSWORD_RESET, code);
        if (account.isEmpty()) {
            return false;
        }
        storePassword(account.get(), newPassword);
        return true;
    }

    /**
     * Redeems a one-time login code. The code is single-use.
     *
     * @return the account to sign in, or empty when the code is wrong, expired, used, or the
     *         account is unknown/disabled
     */
    public Optional<UserAccount> redeemLoginCode(String login, String code) {
        return redeem(login, AuthCodePurpose.LOGIN, code);
    }

    private Optional<UserAccount> redeem(String login, AuthCodePurpose purpose, String code) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        Optional<UserAccount> account = store.findByLogin(login).filter(UserAccount::enabled);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        boolean ok = codes.redeem(JdbcUserAccountStore.key(account.get().login()), purpose, code,
                clock.instant(), settings.maxAttempts());
        return ok ? account : Optional.empty();
    }

    // ------------------------------------------------------------------ session liveness

    /**
     * Whether the login still belongs to an enabled account. Answers are cached for
     * {@link Settings#statusCacheTtl()} so the per-request check costs no query on the hot path; a
     * change made on this node is visible at once, on other nodes after the interval.
     */
    public boolean isActive(String login) {
        String key = JdbcUserAccountStore.key(login);
        long now = System.nanoTime();
        Status cached = statusCache.get(key);
        if (cached != null && now - cached.checkedAtNanos() < settings.statusCacheTtl().toNanos()) {
            return cached.active();
        }
        boolean active = store.findByLogin(login).map(UserAccount::enabled).orElse(false);
        if (statusCache.size() > 10_000) {
            statusCache.clear();
        }
        statusCache.put(key, new Status(active, now));
        return active;
    }

    // ------------------------------------------------------------------ internals

    private UserAccount require(UUID id) {
        return store.findById(id).orElseThrow(() -> new IllegalArgumentException("No such user: " + id));
    }

    private UserAccount storePassword(UserAccount account, String password) {
        UserAccount updated = account.withPasswordHash(encode(password));
        store.update(updated);
        codes.invalidateAll(JdbcUserAccountStore.key(account.login()), clock.instant());
        return updated;
    }

    private String encode(String password) {
        validatePassword(password);
        return passwordEncoder.encode(password);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < settings.passwordMinLength()) {
            throw new IllegalArgumentException(
                    "Password must be at least " + settings.passwordMinLength() + " characters");
        }
        if (password.length() > 256) {
            throw new IllegalArgumentException("Password must be at most 256 characters");
        }
    }

    private static Set<String> normalizeRoles(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                String name = role == null ? "" : role.trim();
                if (name.isEmpty() || name.contains(",") || name.startsWith("ROLE_") || name.length() > 100) {
                    throw new IllegalArgumentException("Invalid role name: '" + role
                            + "' (use the bare name, e.g. ADMIN — no ROLE_ prefix, no commas)");
                }
                out.add(name);
            }
        }
        return out;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
