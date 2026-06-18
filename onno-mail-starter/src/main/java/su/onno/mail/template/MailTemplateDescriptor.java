package su.onno.mail.template;

public record MailTemplateDescriptor(
        Class<?> target,
        String name,
        String subject,
        String template,
        boolean html,
        String replyTo
) {
}
