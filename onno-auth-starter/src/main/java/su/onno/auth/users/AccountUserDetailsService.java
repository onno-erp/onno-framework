package su.onno.auth.users;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Loads {@link AccountUserDetails} from the {@link UserStore} for password and remember-me sign-in. */
public class AccountUserDetailsService implements UserDetailsService {

    private final UserStore store;

    public AccountUserDetailsService(UserStore store) {
        this.store = store;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return store.findCredentials(username)
                .map(c -> new AccountUserDetails(c.account(), c.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("No account " + username));
    }
}
