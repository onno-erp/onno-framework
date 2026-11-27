package su.onno.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import su.onno.auth.spi.AuthPathsContributor;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class CsrfPathPolicyTest {

    private WebApplicationContextRunner runner(String mode) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class, OnnoAuthAutoConfiguration.class))
                .withUserConfiguration(OnnoAuthAutoConfigurationTest.ClientRegistrationConfig.class,
                        OnnoAuthAutoConfigurationTest.JwtDecoderConfig.class)
                .withBean(DataSource.class, () -> {
                    var source = new JdbcDataSource();
                    source.setURL("jdbc:h2:mem:csrf-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                    return source;
                })
                .withPropertyValues("onno.auth.mode=" + mode,
                        "onno.auth.session.remember-me.enabled=false");
    }

    @Test
    void legacyReplacementAndEmptyListCannotRemoveLoginExemptionInCookieModes() {
        for (String mode : List.of("in-memory", "database", "oidc")) {
            for (String setting : List.of("onno.auth.csrf-ignored-paths[0]=/api/legacy/**",
                    "onno.auth.csrf-ignored-paths=")) {
                runner(mode).withPropertyValues(setting).run(context -> {
                    assertThat(context).hasNotFailed();
                    var chain = context.getBean(SecurityFilterChain.class);
                    assertStatus(chain, "/api/auth/login", 200);
                    assertStatus(chain, "/api/auth/logout", 403);
                    assertStatus(chain, "/api/private", 403);
                    assertStatus(chain, "/api/auth/login/custom", 403);
                    assertStatus(chain, "/api/auth/login/custom/challenge", 403);
                });
            }
        }
    }

    @Test
    void legacyAndAdditionalPatternsAreAdditiveAndDoNotGrantAnonymousAccess() {
        for (String mode : List.of("in-memory", "database", "oidc")) {
            runner(mode).withPropertyValues("onno.auth.csrf-ignored-paths[0]=/api/legacy/**",
                            "onno.auth.additional-csrf-ignored-paths[0]=/api/extra/**",
                            "onno.auth.additional-csrf-ignored-paths[1]=/api/legacy/**",
                            "onno.auth.additional-public-paths[0]=/api/legacy/**")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var chain = context.getBean(SecurityFilterChain.class);
                        assertStatus(chain, "/api/auth/login", 200);
                        assertStatus(chain, "/api/legacy/form", 200);
                        // CSRF is exempt, but this endpoint is still authenticated.
                        assertStatus(chain, "/api/extra/form", 401);
                        assertStatus(chain, "/api/private", 403);
                        assertThat(context.getBean(OnnoAuthPaths.class).csrfIgnoredPaths())
                                .containsExactly("/api/auth/login", "/api/legacy/**", "/api/extra/**");
                    });
        }
    }

    @Test
    void contributorsComposeWithoutCouplingPublicAccessAndCsrfExemption() {
        for (String mode : List.of("in-memory", "database", "oidc", "resource-server")) {
            runner(mode).withBean("publicContributor", AuthPathsContributor.class, () -> new AuthPathsContributor() {
                        @Override
                        public List<String> publicPaths() {
                            return List.of("/api/plugin/public", "/api/plugin/webhook");
                        }
                    })
                    .withBean("csrfContributor", AuthPathsContributor.class, () -> new AuthPathsContributor() {
                        @Override
                        public List<String> csrfIgnoredPaths() {
                            return List.of("/api/plugin/webhook", "/api/plugin/private");
                        }
                    })
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var chain = context.getBean(SecurityFilterChain.class);
                        assertStatus(chain, "/api/plugin/webhook", 200);
                        assertStatus(chain, "/api/plugin/private", 401);
                        assertStatus(chain, "/api/plugin/public", mode.equals("resource-server") ? 200 : 403);
                        assertStatus(chain, "/api/unrelated", mode.equals("resource-server") ? 401 : 403);
                    });
        }
    }

    @Test
    void publicConfigurationAloneDoesNotDisableCsrf() {
        runner("in-memory").withPropertyValues("onno.auth.additional-public-paths[0]=/api/form")
                .run(context -> assertStatus(context.getBean(SecurityFilterChain.class), "/api/form", 403));
    }

    @Test
    void actualPasswordLoginWorksWithoutCsrfAfterReplacingLegacyPaths() {
        for (String mode : List.of("in-memory", "database")) {
            runner(mode).withPropertyValues("onno.auth.csrf-ignored-paths[0]=/api/custom/**",
                            "onno.auth.users[0].username=reader@example.test",
                            "onno.auth.users[0].password=TestPassword123",
                            "onno.auth.users[0].roles[0]=USER",
                            "onno.auth.database.seed-password-change-required=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var mvc = MockMvcBuilders.webAppContextSetup(context)
                                .addFilters(new FilterChainProxy(List.of(context.getBean(SecurityFilterChain.class))))
                                .build();
                        var login = mvc.perform(post("/api/auth/login")
                                        .contentType("application/json")
                                        .content("{\"username\":\"reader@example.test\",\"password\":\"TestPassword123\"}"))
                                .andReturn();
                        var response = login.getResponse();
                        assertThat(response.getStatus()).isEqualTo(200);
                        assertThat(response.getContentAsString()).contains("\"authenticated\":true");
                        var session = (MockHttpSession) login.getRequest().getSession(false);
                        assertThat(session).isNotNull();
                        assertThat(mvc.perform(post("/api/auth/logout").session(session))
                                .andReturn().getResponse().getStatus()).isEqualTo(403);
                        var csrf = mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getResponse();
                        var csrfData = new ObjectMapper()
                                .readTree(csrf.getContentAsString());
                        assertThat(mvc.perform(post("/api/auth/logout").session(session)
                                        .cookie(csrf.getCookie("XSRF-TOKEN"))
                                        .header(csrfData.get("headerName").asText(), csrfData.get("token").asText()))
                                .andReturn().getResponse().getStatus()).isEqualTo(204);
                    });
        }
    }

    private static void assertStatus(SecurityFilterChain chain, String path, int expected) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        var response = new MockHttpServletResponse();
        var downstream = new MockFilterChain();
        new FilterChainProxy(List.of(chain)).doFilter(request, response, downstream);
        assertThat(response.getStatus()).as("POST %s", path).isEqualTo(expected);
        if (expected == 200) {
            assertThat(downstream.getRequest()).isNotNull();
        }
    }
}
