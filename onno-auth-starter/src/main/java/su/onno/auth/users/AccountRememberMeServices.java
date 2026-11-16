package su.onno.auth.users;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * Remember-me cookies bound to the account's {@linkplain UserAccount#securityStamp() security
 * stamp}. The stock signature covers username, expiry and password hash, so a cookie would survive
 * "sign out everywhere", a role change or a disable/enable cycle; mixing the stamp in invalidates
 * every outstanding cookie the moment the stamp moves.
 */
public class AccountRememberMeServices extends TokenBasedRememberMeServices {

    private final UserStore store;

    public AccountRememberMeServices(String key, UserDetailsService userDetailsService, UserStore store) {
        super(key, userDetailsService, RememberMeTokenAlgorithm.SHA256);
        this.store = store;
    }

    @Override
    protected String makeTokenSignature(long tokenExpiryTime, String username, String password,
                                        RememberMeTokenAlgorithm algorithm) {
        return super.makeTokenSignature(tokenExpiryTime, username, bound(username, password), algorithm);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected String makeTokenSignature(long tokenExpiryTime, String username, String password) {
        return super.makeTokenSignature(tokenExpiryTime, username, bound(username, password));
    }

    private String bound(String username, String password) {
        long stamp = store.findByUsername(username).map(UserAccount::securityStamp).orElse(-1L);
        return password + ":" + stamp;
    }
}
