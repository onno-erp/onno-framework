package su.onno.ui;

/**
 * Links an authenticated account to a domain catalog record (e.g. an Employee),
 * by matching the principal's login against a catalog field. Lets persona UIs
 * personalize for "the current person" without coupling the framework to any
 * specific domain class.
 *
 * @param javaClass  the catalog class that acts as the user directory
 * @param loginField the catalog field (by fieldName) compared to the login
 */
public record UiIdentityLink(Class<?> javaClass, String loginField) {}
