package su.onno.identity;

/**
 * Links an authenticated account to a domain catalog record (e.g. an Employee) by matching the
 * principal's login against a catalog field. Declare one as a Spring bean; the framework then
 * knows "the current person" (task inbox routing, comment authors, record-access
 * {@code Subject.recordId()}) without coupling to any specific domain class.
 *
 * <pre>
 * &#64;Bean
 * IdentityLink identity() {
 *     return IdentityLink.of(Employee.class, "email");
 * }
 * </pre>
 *
 * @param javaClass  the catalog class that acts as the user directory
 * @param loginField the catalog field (by fieldName) compared to the login
 */
public record IdentityLink(Class<?> javaClass, String loginField) {

    /** Link logins to {@code catalog} records whose {@code loginField} holds the username. */
    public static IdentityLink of(Class<?> catalog, String loginField) {
        return new IdentityLink(catalog, loginField);
    }
}
