package su.onno.ui;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.resource.AbstractResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Falls back to the (base-path-injected) index.html for SPA client-side routes.
 * Actual static files (js, css, images) are served normally.
 */
class SpaResourceResolver extends AbstractResourceResolver {

    private final SpaIndexHtml indexHtml;
    private final String basePath;

    SpaResourceResolver(SpaIndexHtml indexHtml, String basePath) {
        this.indexHtml = indexHtml;
        this.basePath = basePath;
    }

    @Override
    protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                                               List<? extends Resource> locations,
                                               ResourceResolverChain chain) {
        if (!isNavigationRequest(request, requestPath)) {
            return chain.resolveResource(request, requestPath, locations);
        }
        Resource resource = chain.resolveResource(request, requestPath, locations);
        if (resource != null) {
            return resource;
        }
        Resource fallback = indexHtml.resource();
        return fallback != null && fallback.exists() ? fallback : null;
    }

    @Override
    protected String resolveUrlPathInternal(String resourceUrlPath, List<? extends Resource> locations,
                                            ResourceResolverChain chain) {
        return chain.resolveUrlPath(resourceUrlPath, locations);
    }

    private boolean isNavigationRequest(HttpServletRequest request, String requestPath) {
        if (request == null || !HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        // Spring represents an exact resource-handler root such as /ui or /ui/ as ".". That is
        // the mount itself, not a file-shaped request, so it must receive the SPA shell too.
        if ((!requestPath.equals(".") && requestPath.contains(".")) || isExcludedRootPath(requestPath)) {
            return false;
        }
        return MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT)).stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_HTML));
    }

    private boolean isExcludedRootPath(String requestPath) {
        return basePath.isEmpty() && (requestPath.equals("api") || requestPath.startsWith("api/")
                || requestPath.equals("plugins") || requestPath.startsWith("plugins/"));
    }
}
