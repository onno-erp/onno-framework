package su.onno.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

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
}
