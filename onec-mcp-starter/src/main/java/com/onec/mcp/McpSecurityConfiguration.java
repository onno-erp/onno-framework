package com.onec.mcp;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Contributes a dedicated, high-precedence {@link SecurityFilterChain} scoped to the MCP
 * endpoint. It requires HTTP Basic authentication, reusing whatever
 * {@code UserDetailsService}/{@code AuthenticationManager} the application already has
 * (e.g. from {@code onec-auth-starter}), so MCP callers map onto the same users and roles
 * the rest of the system enforces.
 *
 * <p>The chain is {@code securityMatcher}-scoped to {@code onec.mcp.endpoint} and ordered
 * ahead of the application's catch-all chain, so it governs only MCP traffic and leaves
 * the existing UI/API security untouched. It is stateless and CSRF-exempt because MCP
 * clients authenticate per request rather than via a browser session.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(OnecMcpProperties.class)
@ConditionalOnProperty(prefix = "onec.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpSecurityConfiguration {

    @Bean
    @Order(1)
    public SecurityFilterChain onecMcpSecurityFilterChain(HttpSecurity http, OnecMcpProperties properties)
            throws Exception {
        String endpoint = properties.getEndpoint();
        return http
                .securityMatcher(endpoint, endpoint + "/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .build();
    }
}
