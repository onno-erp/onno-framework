package com.onec.ui.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRecipientsTest {

    @Test
    void userAndRecordKeysArePrefixed() {
        assertThat(NotificationRecipients.user("alice")).isEqualTo("user:alice");
        assertThat(NotificationRecipients.record("abc-123")).isEqualTo("record:abc-123");
    }

    @Test
    void roleKeyNormalisesLikeUiAccessService() {
        // Upper-cased and the ROLE_ prefix stripped, so the send side and the resolve side (which reads
        // already-normalised authorities) always produce the same key.
        assertThat(NotificationRecipients.role("finance")).isEqualTo("role:FINANCE");
        assertThat(NotificationRecipients.role("ROLE_finance")).isEqualTo("role:FINANCE");
        assertThat(NotificationRecipients.role("  Admin ")).isEqualTo("role:ADMIN");
    }
}
