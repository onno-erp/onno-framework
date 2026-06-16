package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;

import java.util.Optional;

/**
 * Default {@link MagicLinkUserLookup} over the in-memory accounts in {@code onec.auth.users}. A user
 * opts into magic-link by declaring an {@code email}; the lookup matches it case-insensitively. As a
 * convenience, a user that sets no email but whose {@code username} already looks like an email
 * address is matched on the username, so {@code username: alice@example.com} works with no extra
 * config. Users with neither are simply never returned — magic-link is opt-in per account.
 */
public class PropertiesMagicLinkUserLookup implements MagicLinkUserLookup {

    private final OnecAuthProperties properties;

    public PropertiesMagicLinkUserLookup(OnecAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> usernameForEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalized = email.trim();
        for (OnecAuthProperties.User user : properties.getUsers()) {
            String declared = user.getEmail();
            if (declared != null && !declared.isBlank()) {
                if (declared.trim().equalsIgnoreCase(normalized)) {
                    return Optional.ofNullable(user.getUsername());
                }
                continue;
            }
            // No explicit email: fall back to an email-shaped username so it can be used as-is.
            String username = user.getUsername();
            if (username != null && username.contains("@") && username.equalsIgnoreCase(normalized)) {
                return Optional.of(username);
            }
        }
        return Optional.empty();
    }
}
