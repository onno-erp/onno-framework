package su.onno.auth.spi;

import java.util.Map;

/**
 * An application-defined way to sign in, beside the password form and SSO redirects: a one-time
 * code sent by SMS or e-mail, a magic link, a badge scan, a Telegram confirmation — anything that
 * ends with "this is user X". The app's own React login page drives it
 * ({@code useSignIn().challenge("code", …)} then {@code useSignIn().method("code", …)}); the
 * framework establishes the session exactly as a password login does (session rotation, CSRF
 * cookie, remember-me, last-login stamp), so the method only decides <em>who</em> the caller is.
 *
 * <pre>{@code
 * @Component
 * class EmailCodeLogin implements LoginMethod {
 *     public String id() { return "code"; }
 *
 *     public Map<String, Object> challenge(Map<String, Object> input) {
 *         codes.send((String) input.get("email"));        // throttle and expire codes here
 *         return Map.of("sentTo", mask((String) input.get("email")));
 *     }
 *
 *     public String authenticate(Map<String, Object> credentials) {
 *         return codes.verify((String) credentials.get("email"), (String) credentials.get("code"))
 *                 .orElseThrow(() -> new LoginRejectedException("The code is wrong or has expired"));
 *     }
 * }
 * }</pre>
 *
 * <p>Endpoints (public, CSRF-exempt like {@code /api/auth/login}):
 * {@code POST /api/auth/login/{id}/challenge} runs {@link #challenge}, and
 * {@code POST /api/auth/login/{id}} runs {@link #authenticate} and signs the returned user in.
 * The returned username must name an account the configured user store knows (database or
 * in-memory mode); a disabled or unknown account is refused like a bad password. Custom methods are
 * unavailable in {@code oidc} and {@code resource-server} modes, where the identity provider owns
 * sign-in.
 *
 * <p>The method owns its own abuse controls: code expiry, attempt limits and send throttling. Like
 * the rest of this SPI the contract is plain data with no Spring Security types.
 */
public interface LoginMethod {

    /** The URL segment naming this method, e.g. {@code "code"}: lower-case letters, digits and dashes. */
    String id();

    /**
     * An optional first step, such as sending a one-time code. The returned map is sent to the
     * browser verbatim, so it must not carry the secret itself. The default rejects the call: a
     * method without a first step only implements {@link #authenticate}.
     *
     * @param input the JSON object the login page posted
     * @throws LoginRejectedException to refuse with a message the login page may show
     */
    default Map<String, Object> challenge(Map<String, Object> input) {
        throw new LoginRejectedException("This sign-in method has no challenge step");
    }

    /**
     * Verifies the posted credentials and returns the username to sign in.
     *
     * @param credentials the JSON object the login page posted
     * @return the username of the account to sign in
     * @throws LoginRejectedException when the credentials are wrong; its message is shown to the caller
     */
    String authenticate(Map<String, Object> credentials);
}
