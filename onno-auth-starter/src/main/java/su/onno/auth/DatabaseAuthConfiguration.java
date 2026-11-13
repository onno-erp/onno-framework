package su.onno.auth;

import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import su.onno.auth.users.AuthCodeSender;
import su.onno.auth.users.AuthCodeStore;
import su.onno.auth.users.DatabaseUsers;
import su.onno.auth.users.JdbcUserAccountStore;
import su.onno.auth.users.LoggingAuthCodeSender;
import su.onno.auth.users.UserAccount;
import su.onno.auth.users.UserAccountStore;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mode {@code database}: accounts live in the application database and are managed at runtime. This
 * configuration only swaps the <em>source</em> of users — the session-cookie filter chain, CSRF
 * handling, remember-me and the JSON {@code /api/auth/login} endpoint are the very beans the
 * in-memory mode uses, so the two modes behave identically on the wire.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "onno.auth", name = "mode", havingValue = "database")
class DatabaseAuthConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DatabaseAuthConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(UserAccountStore.class)
    UserAccountStore onnoUserAccountStore(ObjectProvider<Jdbi> jdbi) {
        return new JdbcUserAccountStore(requireJdbi(jdbi));
    }

    @Bean
    @ConditionalOnMissingBean
    AuthCodeStore onnoAuthCodeStore(ObjectProvider<Jdbi> jdbi) {
        return new AuthCodeStore(requireJdbi(jdbi));
    }

    @Bean
    @ConditionalOnMissingBean(AuthCodeSender.class)
    AuthCodeSender onnoAuthCodeSender(OnnoAuthProperties properties) {
        return new LoggingAuthCodeSender(properties.getDatabase().isLogCodes());
    }

    /**
     * Runs {@link AuthCodeSender}s off the request thread, so delivery latency leaks nothing.
     * Deliberately not an {@code Executor} bean: that would switch off Spring Boot's own
     * {@code applicationTaskExecutor}.
     */
    static final class CodeDispatcher {

        private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "onno-auth-code-sender");
            thread.setDaemon(true);
            return thread;
        });

        void dispatch(Runnable task) {
            executor.execute(task);
        }

        void close() {
            executor.shutdown();
        }
    }

    @Bean(destroyMethod = "close")
    CodeDispatcher onnoAuthCodeDispatcher() {
        return new CodeDispatcher();
    }

    @Bean
    @ConditionalOnMissingBean
    DatabaseUsers onnoDatabaseUsers(UserAccountStore store, AuthCodeStore codes, PasswordEncoder passwordEncoder,
                                    AuthCodeSender sender, CodeDispatcher dispatcher,
                                    OnnoAuthProperties properties) {
        OnnoAuthProperties.Database db = properties.getDatabase();
        return new DatabaseUsers(store, codes, passwordEncoder, sender, dispatcher::dispatch,
                new DatabaseUsers.Settings(db.getPasswordMinLength(), db.getPasswordResetValidity(),
                        db.getCodeLoginValidity(), db.getCodeLength(), db.getCodeMaxAttempts(),
                        db.getCodeMaxRequests(), db.getCodeRequestWindow(), db.getAccountRecheckInterval()),
                Clock.systemUTC());
    }

    /**
     * Loads accounts for the password login and for remember-me. A disabled or unknown account is
     * reported as "not found" — Spring's DAO provider turns that into the same bad-credentials
     * failure (after a dummy hash comparison) as a wrong password, so the login response never
     * distinguishes them. An account without a password gets an unmatchable hash.
     */
    @Bean
    UserDetailsService onnoDatabaseUserDetailsService(DatabaseUsers users, PasswordEncoder passwordEncoder) {
        String unusable = passwordEncoder.encode(UUID.randomUUID().toString());
        return username -> {
            UserAccount account = users.find(username).filter(UserAccount::enabled)
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown or disabled user"));
            return User.withUsername(account.login())
                    .password(account.hasPassword() ? account.passwordHash() : unusable)
                    .roles(account.roles().toArray(String[]::new))
                    .build();
        };
    }

    /**
     * Bootstraps accounts from {@code onno.auth.users}: each entry is created once, when no account
     * with that login exists yet. An existing account is never touched, so a password changed at
     * runtime survives restarts. This is how the first administrator comes to exist.
     */
    @Bean
    SmartInitializingSingleton onnoDatabaseUserSeeder(DatabaseUsers users, OnnoAuthProperties properties) {
        return () -> {
            for (OnnoAuthProperties.User seed : properties.getUsers()) {
                if (seed.getUsername() == null || seed.getPassword() == null) {
                    throw new IllegalStateException("onno.auth.users entry is missing username or password");
                }
                if (users.find(seed.getUsername()).isEmpty()) {
                    users.create(seed.getUsername(), seed.getPassword(), seed.getRoles());
                    log.info("Created database user '{}' from onno.auth.users", seed.getUsername());
                }
            }
        };
    }

    @Bean
    DatabaseAuthController onnoDatabaseAuthController(DatabaseUsers users, AuthApiController authApi,
                                                      UserDetailsService userDetailsService,
                                                      ObjectProvider<TokenBasedRememberMeServices> rememberMe,
                                                      OnnoAuthProperties properties) {
        return new DatabaseAuthController(users, authApi, userDetailsService, rememberMe.getIfAvailable(),
                properties.getDatabase());
    }

    private static Jdbi requireJdbi(ObjectProvider<Jdbi> jdbi) {
        Jdbi bean = jdbi.getIfAvailable();
        if (bean == null) {
            throw new IllegalStateException("onno.auth.mode=database needs a Jdbi bean (provided by "
                    + "onno-framework-starter), or your own UserAccountStore and AuthCodeStore beans.");
        }
        return bean;
    }
}
