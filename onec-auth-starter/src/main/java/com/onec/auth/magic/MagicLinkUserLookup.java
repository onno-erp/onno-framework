package com.onec.auth.magic;

import java.util.Optional;

/**
 * Resolves the email a user typed on the login screen to the account it signs in. Kept separate from
 * the token store and the credential check so an application can plug its own directory (a database
 * of users, an LDAP lookup, …) without touching the rest of the flow — register a
 * {@code MagicLinkUserLookup} bean to override the default, which reads {@code onec.auth.users}.
 *
 * <p>Returning {@link Optional#empty()} for an unknown email is normal and expected: the controller
 * responds identically whether or not an account matched, so the endpoint never reveals which
 * addresses are registered.
 */
@FunctionalInterface
public interface MagicLinkUserLookup {

    /**
     * @param email the address entered on the login screen (raw; implementations normalize as needed)
     * @return the username whose authorities back the session, or empty if no account matches
     */
    Optional<String> usernameForEmail(String email);
}
