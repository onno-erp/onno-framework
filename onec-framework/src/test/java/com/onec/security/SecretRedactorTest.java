package com.onec.security;

import com.onec.metadata.AttributeDescriptor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SecretRedactorTest {

    private static AttributeDescriptor attr(String column, boolean secret) {
        return new AttributeDescriptor(column, column, column, String.class, 100, false, false,
                null, 0, 0, true, true, true, 0, "", "", "",
                AttributeDescriptor.Constraints.NONE, secret);
    }

    @Test
    void storedSecret_becomesSentinel_nonSecretUntouched() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ws_password", "enc:abc123");
        row.put("username", "alice");

        SecretRedactor.redact(List.of(row), List.of(attr("ws_password", true), attr("username", false)));

        assertThat(row.get("ws_password")).isEqualTo(SecretRedactor.SET);
        assertThat(row.get("username")).isEqualTo("alice");
    }

    @Test
    void absentSecret_becomesNull() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ws_password", null);

        SecretRedactor.redact(List.of(row), List.of(attr("ws_password", true)));

        assertThat(row).containsKey("ws_password");
        assertThat(row.get("ws_password")).isNull();
    }

    @Test
    void handlesUppercasedColumnFromDriver() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("WS_PASSWORD", "enc:xyz");

        SecretRedactor.redact(List.of(row), List.of(attr("ws_password", true)));

        assertThat(row.get("WS_PASSWORD")).isEqualTo(SecretRedactor.SET);
    }
}
