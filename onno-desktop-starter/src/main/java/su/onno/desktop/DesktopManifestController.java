package su.onno.desktop;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints the Tauri shell drives at launch:
 *
 * <ul>
 *   <li>{@code GET /api/desktop/ready} — returns 200 once the context is up, so
 *       the shell knows when to swap the splash for the real window. Doubles as a
 *       liveness probe, sparing us a dependency on Spring Actuator.</li>
 *   <li>{@code GET /api/desktop/manifest} — the {@link DesktopManifest} built from
 *       the application's {@link DesktopApp} bean: title, geometry, tray, splash.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/desktop")
public class DesktopManifestController {

    private final DesktopManifest manifest;

    public DesktopManifestController(DesktopManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping("/ready")
    public ResponseEntity<Void> ready() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/manifest")
    public DesktopManifest manifest() {
        return manifest;
    }
}
