package com.onec.ui;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Falls back to index.html for SPA client-side routes.
 * Actual static files (js, css, images) are served normally.
 */
class SpaResourceResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource resource = super.getResource(resourcePath, location);
        if (resource != null && resource.exists()) {
            return resource;
        }
        // Fall back to index.html for client-side routes
        Resource fallback = new ClassPathResource("static/ui/index.html");
        return fallback.exists() ? fallback : null;
    }
}
