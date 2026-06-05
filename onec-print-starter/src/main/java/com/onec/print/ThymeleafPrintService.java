package com.onec.print;

import com.lowagie.text.DocumentException;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

public class ThymeleafPrintService implements PrintService {

    private final PrintTemplateRegistry registry;
    private final ResourceLoader resourceLoader;
    private final TemplateEngine engine;
    private final Charset encoding;

    public ThymeleafPrintService(PrintTemplateRegistry registry,
                                 ResourceLoader resourceLoader,
                                 PrintProperties properties) {
        this.registry = registry;
        this.resourceLoader = resourceLoader;
        this.encoding = Charset.forName(properties.getEncoding());

        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode("HTML");
        resolver.setCacheable(false);

        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
    }

    @Override
    public PrintResult render(Object target, String templateName) {
        return render(target, templateName, Map.of());
    }

    @Override
    public PrintResult render(Object target, String templateName, Map<String, Object> extras) {
        if (target == null) {
            throw new IllegalArgumentException("target is null");
        }
        PrintTemplateDescriptor descriptor = registry.find(target.getClass(), templateName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No print template '" + templateName + "' on " + target.getClass().getName()));

        String html = renderHtml(descriptor, target, extras);
        String filename = descriptor.name() + "." + (descriptor.format() == PrintFormat.PDF ? "pdf" : "html");

        return switch (descriptor.format()) {
            case HTML -> new PrintResult(html.getBytes(encoding), PrintFormat.HTML.contentType(), filename);
            case PDF -> new PrintResult(toPdf(html), PrintFormat.PDF.contentType(), filename);
        };
    }

    private String renderHtml(PrintTemplateDescriptor descriptor, Object target, Map<String, Object> extras) {
        Resource resource = resourceLoader.getResource(descriptor.template());
        if (!resource.exists()) {
            throw new IllegalStateException("Print template not found: " + descriptor.template());
        }
        String body;
        try (var in = resource.getInputStream()) {
            body = new String(in.readAllBytes(), encoding);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read template " + descriptor.template(), e);
        }
        Context ctx = new Context();
        ctx.setVariable("doc", target);
        ctx.setVariable("self", target);
        if (extras != null) {
            ctx.setVariable("extra", extras);
            extras.forEach(ctx::setVariable);
        }
        return engine.process(body, ctx);
    }

    private byte[] toPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to render PDF", e);
        }
    }
}
