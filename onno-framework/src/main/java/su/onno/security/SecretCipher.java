package su.onno.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts/decrypts {@code @Attribute(secret = true)} values at rest with AES-256-GCM.
 *
 * <p>The key comes from configuration ({@code onno.security.secret-key}) — never hard-coded —
 * and is SHA-256-hashed to a 256-bit AES key, so any passphrase length is accepted. Each
 * ciphertext carries a fresh random 12-byte IV and is stored as {@code "enc:" + base64(iv || ct)}.
 *
 * <p>Both operations are idempotent at the boundary: {@link #encrypt} returns an already-encrypted
 * value unchanged, and {@link #decrypt} passes through any value lacking the {@code enc:} prefix
 * (legacy plaintext). When no key is configured, the cipher is inert until a secret value is
 * actually written/read, at which point it fails fast with a clear message.
 */
public final class SecretCipher {

    /** Prefix marking a value as produced by this cipher (vs. legacy plaintext). */
    static final String PREFIX = "enc:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            this.key = null;
        } else {
            this.key = new SecretKeySpec(sha256(secretKey), "AES");
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** Encrypts {@code plaintext}; returns null/blank and already-encrypted input unchanged. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (plaintext.startsWith(PREFIX)) return plaintext;
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret attribute", e);
        }
    }

    /** Decrypts a value produced by {@link #encrypt}; passes through null/blank and plaintext. */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) return stored;
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret attribute", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "A @Attribute(secret = true) value was used but no encryption key is configured. " +
                            "Set 'onno.security.secret-key' (e.g. from an environment variable).");
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
