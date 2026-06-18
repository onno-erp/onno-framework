package su.onno.auth.spi;

/**
 * Supplies the {@link AuthMethods} for the running application. Implemented by the auth module and
 * consumed (optionally) by the UI module to render a server-driven login screen.
 *
 * <p>The UI module depends on this interface but not on any auth implementation: it injects the
 * provider through an {@code ObjectProvider} and degrades gracefully (password-only) when no bean is
 * present — e.g. when the auth starter is absent from the classpath.
 */
public interface AuthMethodsProvider {

    /** The currently available authentication methods. Never null. */
    AuthMethods authMethods();
}
