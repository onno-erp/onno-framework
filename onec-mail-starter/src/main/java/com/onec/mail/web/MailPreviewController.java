package com.onec.mail.web;

import com.onec.mail.template.MailRenderer;
import com.onec.mail.template.MailTemplateDescriptor;
import com.onec.mail.template.MailTemplateRegistry;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Dev-only endpoints (enabled via {@code onec.mail.preview.enabled=true}) for inspecting templates:
 * a JSON listing of every registered template, and an in-browser HTML render of one by name using a
 * best-effort sample instance of its target type. Never enable in production.
 */
@RestController
public class MailPreviewController {

    private final MailTemplateRegistry registry;
    private final MailRenderer renderer;

    public MailPreviewController(MailTemplateRegistry registry, MailRenderer renderer) {
        this.registry = registry;
        this.renderer = renderer;
    }

    @GetMapping(path = "${onec.mail.preview.path:/onec/mail/preview}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list() {
        return registry.all().stream()
                .map(d -> Map.<String, Object>of(
                        "name", d.name(),
                        "target", d.target().getName(),
                        "subject", d.subject(),
                        "template", d.template(),
                        "html", d.html()))
                .toList();
    }

    @GetMapping(path = "${onec.mail.preview.path:/onec/mail/preview}/{name}")
    public ResponseEntity<String> preview(@PathVariable String name) {
        MailTemplateDescriptor descriptor = registry.findByName(name).orElse(null);
        if (descriptor == null) {
            return ResponseEntity.notFound().build();
        }
        Object sample;
        try {
            sample = descriptor.target().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return ResponseEntity.status(422).contentType(MediaType.TEXT_PLAIN)
                    .body("Cannot instantiate sample of " + descriptor.target().getName()
                            + " (needs a no-arg constructor): " + e.getMessage());
        }
        try {
            MailRenderer.Rendered rendered = renderer.render(descriptor, sample, Map.of());
            MediaType type = rendered.html() ? MediaType.TEXT_HTML : MediaType.TEXT_PLAIN;
            return ResponseEntity.ok().contentType(type)
                    .body("<!-- subject: " + rendered.subject() + " -->\n" + rendered.body());
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body("Render failed: " + e.getMessage());
        }
    }
}
