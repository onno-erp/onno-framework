package com.onec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@AutoConfiguration(before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties(OnecAuthProperties.class)
@ConditionalOnProperty(prefix = "onec.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OnecAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PasswordEncoder oneCPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    UserDetailsService oneCUserDetailsService(OnecAuthProperties properties, PasswordEncoder passwordEncoder) {
        List<UserDetails> users = properties.getUsers().stream()
                .map(u -> {
                    if (u.getUsername() == null || u.getPassword() == null) {
                        throw new IllegalStateException(
                                "onec.auth.users entry is missing username or password");
                    }
                    String[] roles = u.getRoles() == null ? new String[0]
                            : u.getRoles().toArray(String[]::new);
                    return (UserDetails) User.withUsername(u.getUsername())
                            .password(passwordEncoder.encode(u.getPassword()))
                            .roles(roles)
                            .build();
                })
                .toList();
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    @ConditionalOnMissingBean
    AuthenticationManager oneCAuthenticationManager(UserDetailsService userDetailsService,
                                                    PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider::authenticate;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain oneCSecurityFilterChain(HttpSecurity http, OnecAuthProperties properties)
            throws Exception {
        String[] publicPaths = properties.getPublicPaths().toArray(String[]::new);

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicPaths).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers("/api/auth/login"))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(this::sendUnauthorized))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    @Bean
    AuthApiController oneCAuthApiController(AuthenticationManager authenticationManager) {
        return new AuthApiController(authenticationManager);
    }

    private void sendUnauthorized(HttpServletRequest request,
                                  HttpServletResponse response,
                                  AuthenticationException exception) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        new ObjectMapper().writeValue(response.getOutputStream(),
                Map.of("error", "unauthenticated"));
    }
}
