package com.onec.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher("test-passphrase");

    @Test
    void encryptThenDecrypt_roundTrips() {
        String secret = "hunter2";
        String encrypted = cipher.encrypt(secret);

        assertThat(encrypted).startsWith("enc:").isNotEqualTo(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void encrypt_isNonDeterministic_butBothDecrypt() {
        String a = cipher.encrypt("same");
        String b = cipher.encrypt("same");

        // Fresh IV per call -> different ciphertext, same plaintext on decrypt.
        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("same");
        assertThat(cipher.decrypt(b)).isEqualTo("same");
    }

    @Test
    void encrypt_isIdempotentOnAlreadyEncryptedInput() {
        String once = cipher.encrypt("value");
        assertThat(cipher.encrypt(once)).isEqualTo(once);
    }

    @Test
    void decrypt_passesThroughLegacyPlaintext() {
        assertThat(cipher.decrypt("plain-legacy")).isEqualTo("plain-legacy");
    }

    @Test
    void nullAndEmpty_passThroughUntouched() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("")).isEmpty();
    }

    @Test
    void unconfiguredCipher_failsFastOnlyWhenUsed() {
        SecretCipher unconfigured = new SecretCipher(null);

        assertThat(unconfigured.isConfigured()).isFalse();
        // No key needed to pass through null/blank or legacy plaintext.
        assertThat(unconfigured.encrypt(null)).isNull();
        assertThat(unconfigured.decrypt("plain")).isEqualTo("plain");
        // But a real encrypt fails loudly with a configuration hint.
        assertThatThrownBy(() -> unconfigured.encrypt("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onec.security.secret-key");
    }

    @Test
    void differentKey_cannotDecrypt() {
        String encrypted = cipher.encrypt("topsecret");
        SecretCipher other = new SecretCipher("a-different-passphrase");

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
