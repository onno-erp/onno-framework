package com.onec.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "onec.auth")
public class OnecAuthProperties {

    /**
     * Master switch for the auth starter. When false, no SecurityFilterChain is contributed
     * and the application can wire its own.
     */
    private boolean enabled = true;

    /**
     * Public API/config endpoints permitted without authentication so the login screen can
     * render and authenticate. The SPA shell itself (everything outside {@code /api/**}) is
     * public by default; only {@code /api/**} requires a session.
     */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/error",
            "/api/theme",
            "/api/config",
            "/api/auth/login",
            // Desktop shell liveness + window manifest. The native shell polls these
            // before any user can log in, so they must be reachable unauthenticated;
            // both are non-sensitive (readiness probe + window geometry/title).
            "/api/desktop/ready",
            "/api/desktop/manifest"));

    /**
     * In-memory user accounts. Empty by default — the consuming app supplies them via
     * {@code onec.auth.users[*]}. Production deployments should disable in-memory users
     * and configure their own UserDetailsService.
     */
    private List<User> users = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    public static class User {
        private String username;
        private String password;
        private List<String> roles = new ArrayList<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}
