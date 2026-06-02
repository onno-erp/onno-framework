package com.onec.mail.template;

import com.onec.mail.MailProperties;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Renders mail subject and body templates. Subject is processed inline as a tiny Thymeleaf template
 * so it can reference {@code doc} fields ("Booking #${doc.ref} confirmed").
 *
 * <p>Body templates may pull in shared layouts/fragments via {@code th:insert}/{@code th:replace},
 * resolved against {@code classpath:/mail/} (e.g. {@code ~{layouts/base :: html(~{::content})}}).
 * Fragment lookup is handled by a {@link ClassLoaderTemplateResolver} that precedes the inline
 * {@link StringTemplateResolver} in the resolution chain.
 */
public class MailRenderer {

    private final ResourceLoader resourceLoader;
    private final TemplateEngine engine;
    private final Charset encoding;

    public MailRenderer(ResourceLoader resourceLoader, MailProperties properties) {
        this.resourceLoader = resourceLoader;
        this.encoding = Charset.forName(properties.getEncoding());

        // Order 1: resolve named fragments/layouts under classpath:/mail/. checkExistence=true lets the
        // inline body/subject strings (which are not real resources) fall through to the string resolver.
        ClassLoaderTemplateResolver fragments = new ClassLoaderTemplateResolver();
        fragments.setPrefix("mail/");
        fragments.setSuffix(".html");
        fragments.setTemplateMode("HTML");
        fragments.setCharacterEncoding(encoding.name());
        fragments.setCheckExistence(true);
        fragments.setCacheable(false);
        fragments.setOrder(1);

        // Order 2: treat the passed-in string as the template content itself (subject + body bodies).
        StringTemplateResolver inline = new StringTemplateResolver();
        inline.setTemplateMode("HTML");
        inline.setCacheable(false);
        inline.setOrder(2);

        this.engine = new TemplateEngine();
        this.engine.addTemplateResolver(fragments);
        this.engine.addTemplateResolver(inline);
    }

    public Rendered render(MailTemplateDescriptor descriptor, Object target, Map<String, Object> extras) {
        Context ctx = new Context();
        ctx.setVariable("doc", target);
        ctx.setVariable("self", target);
        if (extras != null) {
            ctx.setVariable("extra", extras);
            extras.forEach(ctx::setVariable);
        }

        String subject = engine.process(descriptor.subject(), ctx);

        Resource resource = resourceLoader.getResource(descriptor.template());
        if (!resource.exists()) {
            throw new IllegalStateException("Mail template not found: " + descriptor.template());
        }
        String body;
        try {
            body = new String(resource.getInputStream().readAllBytes(), encoding);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read template " + descriptor.template(), e);
        }
        String rendered = engine.process(body, ctx);

        return new Rendered(subject, rendered, descriptor.html());
    }

    public record Rendered(String subject, String body, boolean html) {
    }
}
