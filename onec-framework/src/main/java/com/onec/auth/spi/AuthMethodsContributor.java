package com.onec.auth.spi;

import java.util.List;

/**
 * Additively contributes SSO options to the login screen, on top of whatever the single
 * {@link AuthMethodsProvider} already offers. Implemented by connectors that act as an identity
 * provider — e.g. a "Log in with Telegram" starter — so they can surface a sign-in button
 * <em>without</em> replacing the base provider, which remains the one authority for the password
 * flag, mode, and logout URL.
 *
 * <p>The UI module collects <em>every</em> contributor bean (ordered) and appends their
 * {@link #ssoProviders()} to the base {@link AuthMethods#providers()}. Contributors compose:
 * registering one is purely additive and backward compatible, and multiple may coexist.
 *
 * <p>Like the rest of this auth↔UI seam (see {@link AuthMethodsProvider}), the result is plain data
 * with no Spring Security types, so a contributor need not depend on the auth implementation.
 */
@FunctionalInterface
public interface AuthMethodsContributor {

    /** SSO options to append to the login screen. Never null; return an empty list to add nothing. */
    List<SsoProvider> ssoProviders();
}
