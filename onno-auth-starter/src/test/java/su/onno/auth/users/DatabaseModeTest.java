package su.onno.auth.users;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import su.onno.auth.OnnoAuthAutoConfiguration;
import su.onno.auth.spi.AuthMethodsProvider;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseModeTest {

    private static final String DB = "jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private WebApplicationContextRunner runner(String url) {
        return new WebApplicationContextRunner()
                .withPropertyValues(
                        "onno.auth.mode=database",
                        "onno.auth.session.remember-me.allow-ephemeral-key=true",
                        "test.db=" + url)
                .withUserConfiguration(DataSourceConfig.class)
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class,
                        OnnoAuthAutoConfiguration.class));
    }

    @Test
    void wiresTheAccountStoreAndReportsPasswordLogin() {
        runner(freshDb()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UserStore.class);
            assertThat(context).hasSingleBean(UserAccounts.class);
            assertThat(context).hasSingleBean(UserAccountsController.class);
            assertThat(context.getBean(UserDetailsService.class)).isInstanceOf(AccountUserDetailsService.class);
            assertThat(context.getBean(TokenBasedRememberMeServices.class)).isInstanceOf(AccountRememberMeServices.class);
            assertThat(context.getBean(AuthMethodsProvider.class).authMethods().passwordEnabled()).isTrue();
            assertThat(context.getBean(AuthMethodsProvider.class).authMethods().mode()).isEqualTo("database");
        });
    }

    @Test
    void configuredUsersSeedAnEmptyStoreOnceAndAreIgnoredAfterwards() {
        String url = freshDb();
        runner(url).withPropertyValues(
                        "onno.auth.users[0].username=admin@example.test",
                        "onno.auth.users[0].password=admin",
                        "onno.auth.users[0].roles[0]=ADMIN")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserAccount admin = context.getBean(UserAccounts.class).find("admin@example.test").orElseThrow();
                    assertThat(admin.roles()).containsExactly("ADMIN");
                    // The configured password only opens the change-password screen.
                    assertThat(admin.passwordChangeRequired()).isTrue();
                });

        // Second boot against the same database: a different seed list no longer creates accounts.
        runner(url).withPropertyValues(
                        "onno.auth.users[0].username=intruder@example.test",
                        "onno.auth.users[0].password=intruder",
                        "onno.auth.users[0].roles[0]=ADMIN")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserAccounts accounts = context.getBean(UserAccounts.class);
                    assertThat(accounts.find("intruder@example.test")).isEmpty();
                    assertThat(accounts.list()).extracting(UserAccount::username)
                            .containsExactly("admin@example.test");
                });
    }

    enum SeedRole implements su.onno.access.RoleKey { MANAGER, IT }

    @Test
    void theFirstSeedGetsARoleHoldingUsersManageWhenNoSeedHoldsOne() {
        su.onno.access.RoleGrants grants = su.onno.access.RoleGrants.of(SeedRole.class)
                .role(SeedRole.IT, r -> r.permit(su.onno.auth.AuthPermissions.MANAGE_USERS));
        runner(freshDb()).withBean(su.onno.access.RoleGrants.class, () -> grants)
                .withPropertyValues(
                        "onno.auth.users[0].username=first@example.test",
                        "onno.auth.users[0].password=first",
                        "onno.auth.users[0].roles[0]=MANAGER",
                        "onno.auth.users[1].username=second@example.test",
                        "onno.auth.users[1].password=second",
                        "onno.auth.users[1].roles[0]=MANAGER")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserAccounts accounts = context.getBean(UserAccounts.class);
                    assertThat(accounts.find("first@example.test").orElseThrow().roles())
                            .containsExactlyInAnyOrder("MANAGER", "IT");
                    assertThat(accounts.find("second@example.test").orElseThrow().roles())
                            .containsExactly("MANAGER");
                });

        // A seed that already holds the right role changes nothing.
        runner(freshDb()).withBean(su.onno.access.RoleGrants.class, () -> grants)
                .withPropertyValues(
                        "onno.auth.users[0].username=first@example.test",
                        "onno.auth.users[0].password=first",
                        "onno.auth.users[0].roles[0]=MANAGER",
                        "onno.auth.users[1].username=it@example.test",
                        "onno.auth.users[1].password=it",
                        "onno.auth.users[1].roles[0]=it")
                .run(context -> assertThat(context.getBean(UserAccounts.class)
                        .find("first@example.test").orElseThrow().roles()).containsExactly("MANAGER"));
    }

    @Test
    void aSeedThatKeepsAWeakPasswordFailsStartup() {
        runner(freshDb()).withPropertyValues(
                        "onno.auth.database.seed-password-change-required=false",
                        "onno.auth.users[0].username=admin",
                        "onno.auth.users[0].password=admin")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("password policy");
                });
    }

    @Test
    void aPendingPasswordChangeConfinesTheSessionToTheAuthEndpoints() {
        runner(freshDb()).run(context -> {
            UserAccounts accounts = context.getBean(UserAccounts.class);
            accounts.createWithTemporaryPassword("nina", List.of("MANAGER"));
            MockHttpSession session = sessionFor(context, "nina");

            assertThat(call(context, session, "/api/catalogs/Books").getStatus()).isEqualTo(403);
            assertThat(call(context, session, "/api/catalogs/Books").getContentAsString())
                    .contains("password_change_required");
            assertThat(call(context, session, "/api/auth/me").getStatus()).isEqualTo(200);
        });
    }

    @Test
    void disablingOrResettingAnAccountEndsItsLiveSession() {
        runner(freshDb()).run(context -> {
            UserAccounts accounts = context.getBean(UserAccounts.class);
            accounts.create("root", List.of("ADMIN"), "a sturdy password", false);
            UserAccount owen = accounts.create("owen", List.of("MANAGER"), "a sturdy password", false);

            MockHttpSession session = sessionFor(context, "owen");
            assertThat(call(context, session, "/api/catalogs/Books").getStatus()).isEqualTo(200);

            accounts.setEnabled(owen.id(), false);
            MockHttpServletResponse revoked = call(context, session, "/api/catalogs/Books");
            assertThat(revoked.getStatus()).isEqualTo(401);
            assertThat(revoked.getContentAsString()).contains("session_revoked");
            assertThat(session.isInvalid()).isTrue();

            accounts.setEnabled(owen.id(), true);
            MockHttpSession second = sessionFor(context, "owen");
            assertThat(call(context, second, "/api/catalogs/Books").getStatus()).isEqualTo(200);
            accounts.resetPassword(owen.id());
            assertThat(call(context, second, "/api/catalogs/Books").getStatus()).isEqualTo(401);
        });
    }

    private static MockHttpSession sessionFor(ApplicationContext context, String username) {
        UserDetails details = context.getBean(UserDetailsService.class).loadUserByUsername(username);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                details, null, details.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    private static MockHttpServletResponse call(ApplicationContext context, MockHttpSession session, String path)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            new FilterChainProxy(List.of(context.getBean(SecurityFilterChain.class)))
                    .doFilter(request, response, new MockFilterChain());
        } finally {
            SecurityContextHolder.clearContext();
        }
        return response;
    }

    private static String freshDb() {
        return "jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        DataSource dataSource(org.springframework.core.env.Environment env) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(env.getProperty("test.db", DB));
            return ds;
        }
    }
}
