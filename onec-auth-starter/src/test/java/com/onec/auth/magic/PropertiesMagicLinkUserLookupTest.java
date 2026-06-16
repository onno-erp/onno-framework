package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesMagicLinkUserLookupTest {

    private static OnecAuthProperties.User user(String username, String email) {
        OnecAuthProperties.User u = new OnecAuthProperties.User();
        u.setUsername(username);
        u.setEmail(email);
        return u;
    }

    private static PropertiesMagicLinkUserLookup lookup(OnecAuthProperties.User... users) {
        OnecAuthProperties properties = new OnecAuthProperties();
        properties.setUsers(new ArrayList<>(List.of(users)));
        return new PropertiesMagicLinkUserLookup(properties);
    }

    @Test
    void matchesDeclaredEmailCaseInsensitivelyAndTrimmed() {
        PropertiesMagicLinkUserLookup lookup = lookup(user("alice", "Alice@Example.com"));
        assertThat(lookup.usernameForEmail("alice@example.com")).contains("alice");
        assertThat(lookup.usernameForEmail("  ALICE@EXAMPLE.COM  ")).contains("alice");
    }

    @Test
    void fallsBackToEmailShapedUsernameWhenNoEmailDeclared() {
        PropertiesMagicLinkUserLookup lookup = lookup(user("bob@example.com", null));
        assertThat(lookup.usernameForEmail("bob@example.com")).contains("bob@example.com");
    }

    @Test
    void plainUsernameWithoutEmailNeverMatches() {
        PropertiesMagicLinkUserLookup lookup = lookup(user("carol", null));
        assertThat(lookup.usernameForEmail("carol")).isEmpty();
        assertThat(lookup.usernameForEmail("carol@example.com")).isEmpty();
    }

    @Test
    void unknownBlankAndNullAreEmpty() {
        PropertiesMagicLinkUserLookup lookup = lookup(user("alice", "alice@example.com"));
        assertThat(lookup.usernameForEmail("nobody@example.com")).isEmpty();
        assertThat(lookup.usernameForEmail("")).isEmpty();
        assertThat(lookup.usernameForEmail(null)).isEmpty();
    }
}
