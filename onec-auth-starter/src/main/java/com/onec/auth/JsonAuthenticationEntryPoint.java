package com.onec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Map;

/**
 * Answers unauthenticated requests with a {@code 401} + JSON body instead of a redirect, so the
 * SPA's {@code /api/auth/me} heartbeat can detect an expired session uniformly across every auth
 * mode. In OIDC mode the browser still reaches the Keycloak login by navigating to the
 * authorization endpoint directly — this entry point only governs the {@code /api/**} surface.
 */
class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", "unauthenticated"));
    }
}
