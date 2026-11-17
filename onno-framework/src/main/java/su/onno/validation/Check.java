package su.onno.validation;

import java.util.List;

/**
 * One live check: given the record's current (unsaved) values, return the feedback to show. A
 * {@code null} or empty result means "nothing to say". Declared as a lambda or method reference on
 * a {@link Checks} bean, so it can use whatever that bean injected.
 */
@FunctionalInterface
public interface Check {

    List<Feedback> check(CheckContext context);
}
