package su.onno.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import su.onno.auth.spi.AuthMethodsProvider;
import su.onno.auth.users.AuthCodeMessage;
import su.onno.auth.users.AuthCodeSender;
import su.onno.auth.users.DatabaseUsers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** End-to-end behaviour of {@code onno.auth.mode=database} through the real security filter chain. */
class DatabaseAuthModeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withPropertyValues(
                    "onno.auth.mode=database",
                    "onno.auth.session.remember-me.allow-ephemeral-key=true",
                    "onno.auth.database.code-login-enabled=true",
                    "onno.auth.database.account-recheck-interval=0s",
                    "onno.auth.users[0].username=admin@clinic.example",
                    "onno.auth.users[0].password=admin-password",
                    "onno.auth.users[0].roles[0]=ADMIN")
            .withUserConfiguration(DatabaseConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    OnnoAuthAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class DatabaseConfig {

        @Bean
        Jdbi jdbi() {
            return Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        }

        @Bean
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    static class RecordingSender implements AuthCodeSender {
        final List<AuthCodeMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(AuthCodeMessage message) {
            sent.add(message);
        }

        AuthCodeMessage awaitLast(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (sent.size() < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(sent).hasSize(count);
            return sent.get(count - 1);
        }
    }

    /** A browser-like client: keeps the session and echoes the CSRF cookie as a header. */
    private static final class Client {
        private final MockMvc mvc;
        private MockHttpSession session = new MockHttpSession();
        private Cookie csrf;

        Client(AssertableWebApplicationContext context) throws Exception {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters((Filter) new FilterChainProxy(List.of(context.getBean(SecurityFilterChain.class))))
                    .build();
            call(get("/api/auth/me"));
        }

        MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
            request.session(session);
            if (csrf != null) {
                request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
            }
            MvcResult result = mvc.perform(request).andReturn();
            Cookie issued = result.getResponse().getCookie("XSRF-TOKEN");
            if (issued != null && issued.getValue() != null && !issued.getValue().isEmpty()) {
                csrf = issued;
            }
            if (result.getRequest().getSession(false) instanceof MockHttpSession live && !live.isInvalid()) {
                session = live;
            } else {
                session = new MockHttpSession();
            }
            return result;
        }

        MvcResult postJson(String path, String body) throws Exception {
            return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        MvcResult putJson(String path, String body) throws Exception {
            return call(put(path).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        int login(String username, String password) throws Exception {
            return postJson("/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\""
                    + password + "\",\"remember\":false}").getResponse().getStatus();
        }

        JsonNode me() throws Exception {
            return JSON.readTree(call(get("/api/auth/me")).getResponse().getContentAsString());
        }
    }

    @Test
    void wiresTheSharedSessionChainAndSeedsTheBootstrapAdmin() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("onnoSecurityFilterChain");
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasSingleBean(UserDetailsService.class);
            assertThat(context).hasBean("onnoDatabaseUserDetailsService");
            assertThat(context).doesNotHaveBean("onnoUserDetailsService");
            assertThat(context.getBean(AuthMethodsProvider.class).authMethods())
                    .satisfies(methods -> {
                        assertThat(methods.passwordEnabled()).isTrue();
                        assertThat(methods.mode()).isEqualTo("database");
                    });
            assertThat(context.getBean(DatabaseUsers.class).find("admin@clinic.example")).get()
                    .satisfies(admin -> assertThat(admin.roles()).containsExactly("ADMIN"));
            assertThat(context.getBean(OnnoAuthProperties.class).effectivePublicPaths())
                    .contains("/api/auth/password-reset/**", "/api/auth/code/**");
        });
    }

    @Test
    void otherModesDoNotServeTheDatabaseEndpointsOrPublicPaths() {
        new WebApplicationContextRunner()
                .withPropertyValues("onno.auth.session.remember-me.allow-ephemeral-key=true")
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class, OnnoAuthAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DatabaseUsers.class);
                    assertThat(context).doesNotHaveBean(DatabaseAuthController.class);
                    assertThat(context).hasBean("onnoUserDetailsService");
                    assertThat(context.getBean(OnnoAuthProperties.class).effectivePublicPaths())
                            .doesNotContain("/api/auth/password-reset/**");
                });
    }

    @Test
    void seededPasswordChangedAtRuntimeSurvivesRestartSeeding() {
        runner.run(context -> {
            DatabaseUsers users = context.getBean(DatabaseUsers.class);
            var admin = users.find("admin@clinic.example").orElseThrow();
            users.setPassword(admin.id(), "changed-at-runtime");

            // Re-running the seeder (as a restart would) must not reset the password.
            context.getBean("onnoDatabaseUserSeeder",
                    org.springframework.beans.factory.SmartInitializingSingleton.class).afterSingletonsInstantiated();

            Client client = new Client(context);
            assertThat(client.login("admin@clinic.example", "admin-password")).isEqualTo(401);
            assertThat(client.login("admin@clinic.example", "changed-at-runtime")).isEqualTo(200);
        });
    }

    @Test
    void adminManagesUsersAndDisablingEndsLiveSessions() {
        runner.run(context -> {
            Client admin = new Client(context);
            assertThat(admin.login("ADMIN@clinic.example", "admin-password")).isEqualTo(200);
            assertThat(admin.me().get("mode").asText()).isEqualTo("database");
            assertThat(admin.me().get("username").asText()).isEqualTo("admin@clinic.example");

            MvcResult created = admin.postJson("/api/auth/users",
                    "{\"username\":\"vet@clinic.example\",\"password\":\"vet-password\",\"roles\":[\"VET\"]}");
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            JsonNode vet = JSON.readTree(created.getResponse().getContentAsString());
            assertThat(vet.has("passwordHash")).isFalse();
            assertThat(created.getResponse().getContentAsString()).doesNotContain("$2a$");
            String vetId = vet.get("id").asText();

            assertThat(admin.postJson("/api/auth/users",
                    "{\"username\":\"VET@clinic.example\",\"password\":\"vet-password\"}")
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(JSON.readTree(admin.call(get("/api/auth/users")).getResponse().getContentAsString()))
                    .hasSize(2);

            Client vetClient = new Client(context);
            assertThat(vetClient.login("vet@clinic.example", "vet-password")).isEqualTo(200);
            assertThat(vetClient.me().get("roles").toString()).contains("ROLE_VET");
            // Not an admin: the administration API is closed.
            assertThat(vetClient.call(get("/api/auth/users")).getResponse().getStatus()).isEqualTo(403);
            assertThat(vetClient.postJson("/api/auth/users", "{\"username\":\"x\",\"password\":\"12345678\"}")
                    .getResponse().getStatus()).isEqualTo(403);

            // Role change is picked up at the next sign-in.
            assertThat(admin.putJson("/api/auth/users/" + vetId, "{\"roles\":[\"VET\",\"MANAGER\"]}")
                    .getResponse().getStatus()).isEqualTo(200);

            // Disable: the live session dies, and the password no longer signs in.
            assertThat(admin.putJson("/api/auth/users/" + vetId, "{\"enabled\":false}")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(vetClient.me().get("authenticated").asBoolean()).isFalse();
            assertThat(vetClient.login("vet@clinic.example", "vet-password")).isEqualTo(401);

            assertThat(admin.putJson("/api/auth/users/" + vetId, "{\"enabled\":true}")
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(vetClient.login("vet@clinic.example", "vet-password")).isEqualTo(200);
            assertThat(vetClient.me().get("roles").toString()).contains("ROLE_MANAGER");
        });
    }

    @Test
    void adminCannotLockThemselvesOut() {
        runner.run(context -> {
            Client admin = new Client(context);
            admin.login("admin@clinic.example", "admin-password");
            String id = context.getBean(DatabaseUsers.class).find("admin@clinic.example").orElseThrow()
                    .id().toString();

            assertThat(admin.putJson("/api/auth/users/" + id, "{\"enabled\":false}")
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(admin.putJson("/api/auth/users/" + id, "{\"roles\":[\"VET\"]}")
                    .getResponse().getStatus()).isEqualTo(409);
            assertThat(admin.me().get("authenticated").asBoolean()).isTrue();
        });
    }

    @Test
    void mutationsStayCsrfProtectedLikeTheRestOfTheApi() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters((Filter) new FilterChainProxy(List.of(context.getBean(SecurityFilterChain.class))))
                    .build();
            // No CSRF token: rejected, exactly like any other API mutation. Login alone is exempt.
            assertThat(mvc.perform(post("/api/auth/password-reset/request")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"a\"}"))
                    .andReturn().getResponse().getStatus()).isIn(401, 403);
            assertThat(mvc.perform(post("/api/auth/code/login")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"a\",\"code\":\"1\"}"))
                    .andReturn().getResponse().getStatus()).isIn(401, 403);
        });
    }

    @Test
    void passwordResetDoesNotRevealWhetherALoginExists() {
        runner.run(context -> {
            RecordingSender sender = context.getBean(RecordingSender.class);
            Client anonymous = new Client(context);

            MvcResult unknown = anonymous.postJson("/api/auth/password-reset/request",
                    "{\"username\":\"nobody@clinic.example\"}");
            MvcResult known = anonymous.postJson("/api/auth/password-reset/request",
                    "{\"username\":\"admin@clinic.example\"}");

            assertThat(unknown.getResponse().getStatus()).isEqualTo(202);
            assertThat(known.getResponse().getStatus()).isEqualTo(202);
            assertThat(unknown.getResponse().getContentAsString())
                    .isEqualTo(known.getResponse().getContentAsString());
            String token = sender.awaitLast(1).code();

            MvcResult wrongUnknown = anonymous.postJson("/api/auth/password-reset/confirm",
                    "{\"username\":\"nobody@clinic.example\",\"code\":\"x\",\"password\":\"brand-new-pass\"}");
            MvcResult wrongKnown = anonymous.postJson("/api/auth/password-reset/confirm",
                    "{\"username\":\"admin@clinic.example\",\"code\":\"x\",\"password\":\"brand-new-pass\"}");
            assertThat(wrongUnknown.getResponse().getStatus()).isEqualTo(400);
            assertThat(wrongKnown.getResponse().getStatus()).isEqualTo(400);
            assertThat(wrongUnknown.getResponse().getContentAsString())
                    .isEqualTo(wrongKnown.getResponse().getContentAsString());

            assertThat(anonymous.postJson("/api/auth/password-reset/confirm",
                    "{\"username\":\"admin@clinic.example\",\"code\":\"" + token
                            + "\",\"password\":\"brand-new-pass\"}").getResponse().getStatus()).isEqualTo(204);
            // Single use.
            assertThat(anonymous.postJson("/api/auth/password-reset/confirm",
                    "{\"username\":\"admin@clinic.example\",\"code\":\"" + token
                            + "\",\"password\":\"another-new-pass\"}").getResponse().getStatus()).isEqualTo(400);

            assertThat(anonymous.login("admin@clinic.example", "admin-password")).isEqualTo(401);
            assertThat(anonymous.login("admin@clinic.example", "brand-new-pass")).isEqualTo(200);
        });
    }

    @Test
    void oneTimeCodeSignsInOnce() {
        runner.run(context -> {
            RecordingSender sender = context.getBean(RecordingSender.class);
            Client client = new Client(context);

            assertThat(client.postJson("/api/auth/code/request", "{\"username\":\"admin@clinic.example\"}")
                    .getResponse().getStatus()).isEqualTo(202);
            assertThat(client.postJson("/api/auth/code/request", "{\"username\":\"nobody\"}")
                    .getResponse().getStatus()).isEqualTo(202);
            String code = sender.awaitLast(1).code();

            assertThat(client.postJson("/api/auth/code/login",
                    "{\"username\":\"nobody\",\"code\":\"" + code + "\"}").getResponse().getStatus())
                    .isEqualTo(401);
            MvcResult login = client.postJson("/api/auth/code/login",
                    "{\"username\":\"admin@clinic.example\",\"code\":\"" + code + "\",\"remember\":false}");
            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            assertThat(JSON.readTree(login.getResponse().getContentAsString()).get("roles").toString())
                    .contains("ROLE_ADMIN");
            assertThat(client.me().get("authenticated").asBoolean()).isTrue();

            Client second = new Client(context);
            assertThat(second.postJson("/api/auth/code/login",
                    "{\"username\":\"admin@clinic.example\",\"code\":\"" + code + "\"}")
                    .getResponse().getStatus()).isEqualTo(401);
        });
    }

    @Test
    void codeLoginIsOffByDefaultAndSelfServiceChangeNeedsTheCurrentPassword() {
        runner.withPropertyValues("onno.auth.database.code-login-enabled=false").run(context -> {
            Client client = new Client(context);
            assertThat(client.postJson("/api/auth/code/request", "{\"username\":\"admin@clinic.example\"}")
                    .getResponse().getStatus()).isEqualTo(404);

            // Anonymous callers cannot reach the self-service change.
            assertThat(client.postJson("/api/auth/password",
                    "{\"currentPassword\":\"admin-password\",\"newPassword\":\"next-password\"}")
                    .getResponse().getStatus()).isEqualTo(401);

            client.login("admin@clinic.example", "admin-password");
            assertThat(client.postJson("/api/auth/password",
                    "{\"currentPassword\":\"wrong\",\"newPassword\":\"next-password\"}")
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(client.postJson("/api/auth/password",
                    "{\"currentPassword\":\"admin-password\",\"newPassword\":\"short\"}")
                    .getResponse().getStatus()).isEqualTo(400);
            assertThat(client.postJson("/api/auth/password",
                    "{\"currentPassword\":\"admin-password\",\"newPassword\":\"next-password\"}")
                    .getResponse().getStatus()).isEqualTo(204);

            Client fresh = new Client(context);
            assertThat(fresh.login("admin@clinic.example", "next-password")).isEqualTo(200);
        });
    }
}
