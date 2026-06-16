package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Orchestrates passwordless magic-link login: issuing + emailing a single-use token on request, and
 * validating one on verify. Deliberately self-contained — it owns token generation/hashing and
 * delegates the three pluggable concerns (who the email maps to, where tokens live, how the link is
 * delivered) to {@link MagicLinkUserLookup}, {@link MagicLinkTokenStore}, and {@link MagicLinkSender}.
 *
 * <p>Security properties: tokens are 256 bits of {@link SecureRandom} entropy; only their SHA-256
 * hash is persisted; {@link #verify} consumes single-use and expiry-checked atomically; and
 * {@link #requestLink} returns no signal about whether an account matched (no email enumeration).
 */
public class MagicLinkService {

    private static final Logger log = LoggerFactory.getLogger(MagicLinkService.class);
    private static final String VERIFY_PATH = "/api/auth/magic/verify";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MagicLinkUserLookup userLookup;
    private final MagicLinkTokenStore tokenStore;
    private final MagicLinkSender sender;
    private final UserDetailsService userDetailsService;
    private final OnecAuthProperties properties;

    public MagicLinkService(MagicLinkUserLookup userLookup,
                            MagicLinkTokenStore tokenStore,
                            MagicLinkSender sender,
                            UserDetailsService userDetailsService,
                            OnecAuthProperties properties) {
        this.userLookup = userLookup;
        this.tokenStore = tokenStore;
        this.sender = sender;
        this.userDetailsService = userDetailsService;
        this.properties = properties;
    }

    /**
     * Issues a link for {@code email} and delivers it — but only if the address maps to an account.
     * For an unknown address it does nothing and returns normally, so the caller can respond
     * identically either way and never reveal which addresses are registered.
     *
     * @param email             the address entered on the login screen
     * @param requestBaseUrl    the origin (scheme://host[:port]) of the incoming request, used to
     *                          build the absolute link when {@code onec.auth.magic-link.base-url} is unset
     */
    public void requestLink(String email, String requestBaseUrl) {
        Optional<String> username = userLookup.usernameForEmail(email);
        if (username.isEmpty()) {
            log.debug("[magic-link] no account for requested email; nothing sent");
            return;
        }
        Duration validity = properties.getMagicLink().getTokenValidity();
        String rawToken = newToken();
        Instant expiresAt = Instant.now().plus(validity);
        tokenStore.save(hash(rawToken), username.get(), expiresAt);

        String link = buildLink(requestBaseUrl, rawToken);
        try {
            sender.send(email.trim(), link, validity);
        } catch (RuntimeException ex) {
            // Don't let a delivery failure change the response shape (which would leak whether the
            // address exists). Surface it in logs for operators instead.
            log.error("[magic-link] failed to deliver sign-in link", ex);
        }
    }

    /**
     * Validates and consumes a token, returning an {@link Authentication} to establish on success.
     * Empty when the token is missing, unknown, already used, expired, or the account it referenced
     * no longer exists.
     */
    public Optional<Authentication> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<String> username = tokenStore.consume(hash(rawToken), Instant.now());
        if (username.isEmpty()) {
            return Optional.empty();
        }
        try {
            UserDetails user = userDetailsService.loadUserByUsername(username.get());
            return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                    user, null, user.getAuthorities()));
        } catch (UsernameNotFoundException ex) {
            // The account was removed between issuing and following the link — treat as invalid.
            log.warn("[magic-link] token referenced an unknown user; rejecting");
            return Optional.empty();
        }
    }

    private String buildLink(String requestBaseUrl, String rawToken) {
        String configured = properties.getMagicLink().getBaseUrl();
        String base = (configured != null && !configured.isBlank()) ? configured.trim() : requestBaseUrl;
        if (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String encoded = URLEncode(rawToken);
        return (base == null ? "" : base) + VERIFY_PATH + "?token=" + encoded;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex-encoded SHA-256 of the raw token — what we persist, so the store never holds the credential. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String URLEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
