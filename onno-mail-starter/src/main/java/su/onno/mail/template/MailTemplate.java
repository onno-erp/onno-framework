package su.onno.mail.template;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a mail template attached to a document or catalog class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(MailTemplates.class)
public @interface MailTemplate {

    /** Stable identifier, e.g. "booking-confirmed". */
    String name();

    /** Subject line. May contain Thymeleaf expressions referring to {@code doc}. */
    String subject();

    /**
     * Body template. Resolved by the resource loader.
     * Default convention: {@code classpath:/mail/{name}.html}.
     */
    String template() default "";

    /** When true, body is sent as HTML. */
    boolean html() default true;

    /** Optional default reply-to. */
    String replyTo() default "";
}
