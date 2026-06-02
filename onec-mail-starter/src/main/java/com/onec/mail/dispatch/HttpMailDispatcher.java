package com.onec.mail.dispatch;

import com.onec.mail.MailDeliveryException;
import com.onec.mail.MailMessage;
import com.onec.mail.MailProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Universal provider adapter ({@code provider=http}). POSTs a rendered, provider-specific JSON body
 * to any mail API's REST endpoint, so a new provider can be onboarded with config only — no new code.
 *
 * <p>The JSON payload is produced by the Thymeleaf template at {@code onec.mail.http.body-template}
 * (TEXT mode). The {@link MailMessage} is exposed as {@code msg}, and {@code json.str(x)} yields a
 * correctly-escaped, quoted JSON string. Example fragment for a SendGrid-style API:
 * <pre>{@code
 * {
 *   "from": { "email": [(${json.str(msg.from)})] },
 *   "subject": [(${json.str(msg.subject)})],
 *   "content": [ { "type": "text/html", "value": [(${json.str(msg.html)})] } ]
 * }
 * }</pre>
 */
public class HttpMailDispatcher implements MailDispatcher {

    private final RestClient client;
    private final ResourceLoader resourceLoader;
    private final MailProperties properties;
    private final TemplateEngine engine;
    private final JsonHelper jsonHelper;
    private final Charset encoding;

    public HttpMailDispatcher(RestClient.Builder restClientBuilder,
                              ResourceLoader resourceLoader,
                              ObjectMapper objectMapper,
                              MailProperties properties) {
        this.client = restClientBuilder.build();
        this.resourceLoader = resourceLoader;
        this.properties = properties;
        this.jsonHelper = new JsonHelper(objectMapper);
        this.encoding = Charset.forName(properties.getEncoding());

        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode("TEXT");
        resolver.setCacheable(false);
        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public void dispatch(MailMessage message) {
        MailProperties.Http cfg = properties.getHttp();
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new MailDeliveryException("onec.mail.http.url is not configured");
        }
        if (cfg.getBodyTemplate() == null || cfg.getBodyTemplate().isBlank()) {
            throw new MailDeliveryException("onec.mail.http.body-template is not configured");
        }

        String body = renderBody(cfg.getBodyTemplate(), message);

        var request = client.method(HttpMethod.valueOf(cfg.getMethod().toUpperCase()))
                .uri(cfg.getUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        cfg.getHeaders().forEach(request::header);

        var response = request.retrieve()
                .onStatus(status -> status.value() > cfg.getSuccessStatusMax(), (req, res) -> {
                    throw new MailDeliveryException("HTTP provider returned " + res.getStatusCode()
                            + " for " + cfg.getUrl());
                })
                .toBodilessEntity();

        if (response.getStatusCode().value() > cfg.getSuccessStatusMax()) {
            throw new MailDeliveryException("HTTP provider returned " + response.getStatusCode());
        }
    }

    private String renderBody(String templateLocation, MailMessage message) {
        Resource resource = resourceLoader.getResource(templateLocation);
        if (!resource.exists()) {
            throw new MailDeliveryException("HTTP body template not found: " + templateLocation);
        }
        String template;
        try {
            template = new String(resource.getInputStream().readAllBytes(), encoding);
        } catch (IOException e) {
            throw new MailDeliveryException("Failed to read HTTP body template " + templateLocation, e);
        }
        Context ctx = new Context();
        ctx.setVariable("msg", message);
        ctx.setVariable("json", jsonHelper);
        return engine.process(template, ctx);
    }

    /** Exposed to templates as {@code json}; renders a value as a quoted, escaped JSON literal. */
    public static final class JsonHelper {
        private final ObjectMapper objectMapper;

        JsonHelper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        /** Returns a JSON-encoded literal for {@code value}, e.g. {@code "a\"b"} or {@code null}. */
        public String str(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new MailDeliveryException("Failed to JSON-encode mail field", e);
            }
        }

        /** Convenience for a list/array as a JSON array literal. */
        public String arr(Object value) {
            return str(value);
        }
    }
}
