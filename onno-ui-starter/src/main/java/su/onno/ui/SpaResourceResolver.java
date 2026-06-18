package su.onno.ui;

import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Falls back to the (base-path-injected) index.html for SPA client-side routes.
 * Actual static files (js, css, images) are served normally.
 */
class SpaResourceResolver extends PathResourceResolver {

    private final SpaIndexHtml indexHtml;

    SpaResourceResolver(SpaIndexHtml indexHtml) {
        this.indexHtml = indexHtml;
    }

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource resource = super.getResource(resourcePath, location);
        if (resource != null && resource.exists()) {
            return resource;
        }
        // Fall back to the injected index.html for client-side routes (deep links).
        Resource fallback = indexHtml.resource();
        return fallback != null && fallback.exists() ? fallback : null;
    }
}
