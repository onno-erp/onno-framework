package su.onno.auth.users;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUsersTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final MutableClock clock = new MutableClock();
    private final List<AuthCodeMessage> sent = new ArrayList<>();
    private Jdbi jdbi;
    private DatabaseUsers users;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        users = new DatabaseUsers(new JdbcUserAccountStore(jdbi), new AuthCodeStore(jdbi), encoder,
                sent::add, Runnable::run, DatabaseUsers.Settings.defaults(), clock);
    }

    @Test
    void createsAccountsWithHashedPasswordsAndCaseInsensitiveLogins() {
        UserAccount anna = users.create("Anna@Clinic.example", "s3cret-pass", List.of("VET", "ADMIN"));

        assertThat(anna.passwordHash()).isNotEqualTo("s3cret-pass");
        assertThat(encoder.matches("s3cret-pass", anna.passwordHash())).isTrue();
        assertThat(users.find("anna@clinic.EXAMPLE")).get()
                .satisfies(found -> {
                    assertThat(found.login()).isEqualTo("Anna@Clinic.example");
                    assertThat(found.roles()).containsExactlyInAnyOrder("VET", "ADMIN");
                    assertThat(found.enabled()).isTrue();
                });
        assertThatThrownBy(() -> users.create("ANNA@clinic.example", "another-pass", List.of()))
                .isInstanceOf(DuplicateLoginException.class);
    }

    @Test
    void rejectsWeakPasswordsAndMalformedRoles() {
        assertThatThrownBy(() -> users.create("a@x", "short", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 8");
        assertThatThrownBy(() -> users.create("a@x", "long-enough", List.of("ROLE_ADMIN")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid role");
        assertThatThrownBy(() -> users.create(" ", "long-enough", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(users.list()).isEmpty();
    }

    @Test
    void adminCanChangeRolesDisableAndSetPassword() {
        UserAccount bob = users.create("bob", null, List.of("VET"));
        assertThat(bob.hasPassword()).isFalse();

        users.setRoles(bob.id(), List.of("MANAGER"));
        users.setPassword(bob.id(), "new-password");
        users.setEnabled(bob.id(), false);

        UserAccount stored = users.findById(bob.id()).orElseThrow();
        assertThat(stored.roles()).isEqualTo(Set.of("MANAGER"));
        assertThat(stored.enabled()).isFalse();
        assertThat(encoder.matches("new-password", stored.passwordHash())).isTrue();
        assertThat(users.isActive("bob")).isFalse();
        assertThat(users.isActive("nobody")).isFalse();
    }

    @Test
    void changePasswordRequiresTheCurrentOne() {
        users.create("bob", "old-password", List.of());

        assertThat(users.changePassword("bob", "wrong", "next-password")).isFalse();
        assertThat(users.changePassword("bob", "old-password", "next-password")).isTrue();
        assertThat(encoder.matches("next-password", users.find("bob").orElseThrow().passwordHash())).isTrue();
    }

    @Test
    void passwordResetTokenWorksExactlyOnce() {
        users.create("bob", "old-password", List.of());

        users.requestCode("BOB", AuthCodePurpose.PASSWORD_RESET);

        assertThat(sent).hasSize(1);
        AuthCodeMessage message = sent.get(0);
        assertThat(message.login()).isEqualTo("bob");
        assertThat(message.purpose()).isEqualTo(AuthCodePurpose.PASSWORD_RESET);
        assertThat(message.code()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(message.toString()).doesNotContain(message.code());

        assertThat(users.resetPassword("bob", message.code(), "brand-new-pass")).isTrue();
        assertThat(encoder.matches("brand-new-pass", users.find("bob").orElseThrow().passwordHash())).isTrue();
        assertThat(users.resetPassword("bob", message.code(), "again-new-pass")).isFalse();
    }

    @Test
    void secretsAreStoredHashed() {
        users.create("bob", "old-password", List.of());
        users.requestCode("bob", AuthCodePurpose.LOGIN);

        List<String> stored = jdbi.withHandle(h ->
                h.createQuery("SELECT _code_hash FROM onno_auth_codes").mapTo(String.class).list());
        assertThat(stored).hasSize(1).doesNotContain(sent.get(0).code());
    }

    @Test
    void weakPasswordIsRejectedBeforeTheTokenIsSpent() {
        users.create("bob", "old-password", List.of());
        users.requestCode("bob", AuthCodePurpose.PASSWORD_RESET);
        String token = sent.get(0).code();

        assertThatThrownBy(() -> users.resetPassword("bob", token, "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(users.resetPassword("bob", token, "long-enough-now")).isTrue();
    }

    @Test
    void loginCodeIsNumericSingleUseAndPurposeBound() {
        users.create("bob", "old-password", List.of("VET"));
        users.requestCode("bob", AuthCodePurpose.LOGIN);
        String code = sent.get(0).code();

        assertThat(code).matches("\\d{6}");
        // A login code is not a reset token.
        assertThat(users.resetPassword("bob", code, "brand-new-pass")).isFalse();
        assertThat(users.redeemLoginCode("bob", code)).get()
                .satisfies(account -> assertThat(account.roles()).containsExactly("VET"));
        assertThat(users.redeemLoginCode("bob", code)).isEmpty();
    }

    @Test
    void codesExpire() {
        users.create("bob", "old-password", List.of());
        users.requestCode("bob", AuthCodePurpose.LOGIN);

        clock.advance(Duration.ofMinutes(11));

        assertThat(users.redeemLoginCode("bob", sent.get(0).code())).isEmpty();
    }

    @Test
    void aCodeDiesAfterTooManyWrongAttempts() {
        users.create("bob", "old-password", List.of());
        users.requestCode("bob", AuthCodePurpose.LOGIN);
        String code = sent.get(0).code();
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThat(users.redeemLoginCode("bob", wrong)).isEmpty();
        }

        assertThat(users.redeemLoginCode("bob", code)).isEmpty();
    }

    @Test
    void aNewCodeSupersedesTheOldOne() {
        users.create("bob", "old-password", List.of());
        users.requestCode("bob", AuthCodePurpose.LOGIN);
        users.requestCode("bob", AuthCodePurpose.LOGIN);

        assertThat(sent).hasSize(2);
        if (!sent.get(0).code().equals(sent.get(1).code())) {
            assertThat(users.redeemLoginCode("bob", sent.get(0).code())).isEmpty();
        }
        assertThat(users.redeemLoginCode("bob", sent.get(1).code())).isPresent();
    }

    @Test
    void requestsAreRateLimitedPerLoginAndWindow() {
        users.create("bob", "old-password", List.of());

        for (int i = 0; i < 5; i++) {
            users.requestCode("bob", AuthCodePurpose.LOGIN);
        }
        assertThat(sent).hasSize(3);

        clock.advance(Duration.ofMinutes(16));
        users.requestCode("bob", AuthCodePurpose.LOGIN);
        assertThat(sent).hasSize(4);
    }

    @Test
    void unknownAndDisabledLoginsGetNothingAndRevealNothing() {
        UserAccount bob = users.create("bob", "old-password", List.of());
        users.requestCode("bob", AuthCodePurpose.PASSWORD_RESET);
        String token = sent.get(0).code();
        users.setEnabled(bob.id(), false);
        sent.clear();

        users.requestCode("nobody", AuthCodePurpose.PASSWORD_RESET);
        users.requestCode("bob", AuthCodePurpose.PASSWORD_RESET);
        users.requestCode(null, AuthCodePurpose.LOGIN);

        assertThat(sent).isEmpty();
        // Disabling voided the outstanding token, and re-enabling does not resurrect it.
        users.setEnabled(bob.id(), true);
        assertThat(users.resetPassword("bob", token, "brand-new-pass")).isFalse();
        assertThat(users.resetPassword("nobody", "whatever", "brand-new-pass")).isFalse();
        assertThat(users.redeemLoginCode("nobody", "123456")).isEmpty();
    }

    @Test
    void aFailingSenderDoesNotBreakTheRequest() {
        users = new DatabaseUsers(new JdbcUserAccountStore(jdbi), new AuthCodeStore(jdbi), encoder,
                message -> {
                    throw new IllegalStateException("smtp down");
                }, Runnable::run, DatabaseUsers.Settings.defaults(), clock);
        users.create("bob", "old-password", List.of());

        users.requestCode("bob", AuthCodePurpose.LOGIN);
    }
}
