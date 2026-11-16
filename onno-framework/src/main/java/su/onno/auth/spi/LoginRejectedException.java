package su.onno.auth.spi;

/**
 * Refuses a {@link LoginMethod} step. Answered as {@code 401} with {@code {"message": …}}, which the
 * login page shows as is — so word it for the person signing in and do not reveal whether an
 * account exists.
 */
public class LoginRejectedException extends RuntimeException {

    public LoginRejectedException(String message) {
        super(message);
    }
}
