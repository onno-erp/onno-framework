package su.onno.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import su.onno.auth.spi.LoginMethod;
import su.onno.auth.spi.LoginRejectedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiControllerTest {

    // The controller's only collaborators the csrf endpoint touches are none — pass nulls for the
    // auth manager / remember-me services it doesn't use here.
    private final AuthApiController controller = new AuthApiController(null, null, new OnnoAuthProperties());

    @Test
    void csrfReturnsTheSessionTokenAndItsHeaderAndParameterNames() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CsrfToken.class.getName(),
                new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "tok-123"));

        var res = controller.csrf(request);

        assertThat(res.token()).isEqualTo("tok-123");
        assertThat(res.headerName()).isEqualTo("X-XSRF-TOKEN");
        assertThat(res.parameterName()).isEqualTo("_csrf");
    }

    @Test
    void csrfReturnsNullsWhenNoTokenIsPresent() {
        // resource-server (stateless) mode disables CSRF, so the request carries no token attribute.
        var res = controller.csrf(new MockHttpServletRequest());

        assertThat(res.token()).isNull();
        assertThat(res.headerName()).isNull();
        assertThat(res.parameterName()).isNull();
    }

    @Test
    void successfulLoginRotatesTheExistingSessionId() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "", List.of());
        var loginController = new AuthApiController(
                ignored -> authentication, null, new OnnoAuthProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String preAuthenticationSessionId = request.getSession().getId();

        try {
            var result = loginController.login(
                    new AuthApiController.LoginRequest("alice", "secret", false), request, response);

            assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(request.getSession(false).getId()).isNotEqualTo(preAuthenticationSessionId);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A one-time-code method: "1234" signs in the posted e-mail; the challenge echoes where it sent. */
    private static final LoginMethod CODE = new LoginMethod() {
        @Override
        public String id() {
            return "code";
        }

        @Override
        public Map<String, Object> challenge(Map<String, Object> input) {
            return Map.of("sentTo", input.get("email"));
        }

        @Override
        public String authenticate(Map<String, Object> credentials) {
            if (!"1234".equals(credentials.get("code"))) {
                throw new LoginRejectedException("Wrong code");
            }
            return (String) credentials.get("email");
        }
    };

    private static AuthApiController withCodeLogin() {
        var users = new InMemoryUserDetailsManager(
                User.withUsername("alice@example.com").password("{noop}x").roles("MANAGER").build(),
                User.withUsername("bob@example.com").password("{noop}x").roles("MANAGER").disabled(true).build());
        return new AuthApiController(ignored -> null, null, new OnnoAuthProperties(), null, users, List.of(CODE));
    }

    @Test
    void applicationLoginMethodSignsInTheAccountItNamesAndRotatesTheSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String before = request.getSession().getId();
        try {
            var result = withCodeLogin().loginWith("code",
                    Map.of("email", "alice@example.com", "code", "1234"), request, response);

            assertThat(result.getStatusCode().value()).isEqualTo(200);
            var user = (AuthApiController.AuthUser) result.getBody();
            assertThat(user.authenticated()).isTrue();
            assertThat(user.username()).isEqualTo("alice@example.com");
            assertThat(user.roles()).containsExactly("ROLE_MANAGER");
            assertThat(request.getSession(false).getId()).isNotEqualTo(before);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void applicationLoginMethodRejectionCarriesItsMessage() {
        var result = withCodeLogin().loginWith("code",
                Map.of("email", "alice@example.com", "code", "0000"),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(401);
        assertThat(result.getBody()).isEqualTo(Map.of("message", "Wrong code"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void applicationLoginMethodRefusesUnknownAndDisabledAccountsAlike() {
        var controller = withCodeLogin();
        for (String email : List.of("nobody@example.com", "bob@example.com")) {
            var result = controller.loginWith("code", Map.of("email", email, "code", "1234"),
                    new MockHttpServletRequest(), new MockHttpServletResponse());
            assertThat(result.getStatusCode().value()).isEqualTo(401);
        }
    }

    @Test
    void challengeAnswersTheMethodsResultAndUnknownMethodsAre404() {
        var controller = withCodeLogin();

        assertThat(controller.challenge("code", Map.of("email", "alice@example.com")).getBody())
                .isEqualTo(Map.of("sentTo", "alice@example.com"));
        assertThat(controller.challenge("sms", Map.of()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.loginWith("sms", Map.of(), new MockHttpServletRequest(),
                new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void applicationLoginMethodsAreUnavailableWithoutALocalAccountStore() {
        // OIDC / resource-server: no UserDetailsService is passed.
        var controller = new AuthApiController(null, null, new OnnoAuthProperties(), null, null, List.of(CODE));

        assertThat(controller.loginWith("code", Map.of("code", "1234"), new MockHttpServletRequest(),
                new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void duplicateOrInvalidMethodIdsFailAtStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AuthApiController(
                        null, null, new OnnoAuthProperties(), null, null, List.of(CODE, CODE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("share the id");
        LoginMethod invalid = new LoginMethod() {
            public String id() { return "One Time"; }
            public String authenticate(Map<String, Object> c) { return null; }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AuthApiController(
                        null, null, new OnnoAuthProperties(), null, null, List.of(invalid)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid id");
    }
}
