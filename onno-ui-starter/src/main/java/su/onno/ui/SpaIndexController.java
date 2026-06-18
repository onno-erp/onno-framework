package su.onno.ui;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Serves the SPA shell at the application root. When the UI is mounted under a base path
 * (the default {@code onno.ui.path = /ui}), the bare root is redirected there: React Router renders
 * nothing for a URL outside its {@code basename}, so {@code http://host/} must bounce to
 * {@code http://host/ui} for the app to boot. Deep links ({@code /ui/**}) are served by
 * {@link SpaResourceResolver}.
 */
@RestController
class SpaIndexController {

    private final SpaIndexHtml indexHtml;

    SpaIndexController(SpaIndexHtml indexHtml) {
        this.indexHtml = indexHtml;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        String basePath = indexHtml.basePath();
        if (!"/".equals(basePath)) {
            // Mounted under a prefix — send the root to it so the SPA loads inside its basename.
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(basePath)).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(indexHtml.resource());
    }
}
