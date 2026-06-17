package com.onec.ui;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the two {@code /.well-known} app-association files that let a native mobile app claim this
 * domain's {@code https://} links — iOS Universal Links and Android App Links — so a tapped link
 * opens the app instead of the browser.
 *
 * <p>These must be reachable <em>unauthenticated</em>, as {@code application/json}, with no redirect:
 * the OS fetches them anonymously and rejects the association on any auth wall or redirect. They sit
 * outside {@code /api/**}, which the auth starter leaves public by default, and being an explicit
 * controller mapping they take precedence over the SPA's {@code /**} index.html fallback (which would
 * otherwise answer these paths with HTML).
 *
 * <p>Both endpoints answer {@code 404} until the matching identities are configured under
 * {@code onec.app-links} (see {@link AppLinksProperties}) — so an unconfigured deployment advertises
 * no association rather than a broken/empty one.
 */
@RestController
public class WellKnownController {

    private final AppLinksProperties properties;

    public WellKnownController(AppLinksProperties properties) {
        this.properties = properties;
    }

    /**
     * iOS {@code apple-app-site-association} (note: no file extension). Grants the configured
     * {@code appID}s Universal-Link handling for every path on this domain.
     */
    @GetMapping(value = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        List<String> appIds = properties.getAppleAppIds();
        if (appIds == null || appIds.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("appIDs", new ArrayList<>(appIds));
        // A single component matching every path. `components` (iOS 13+) is the modern form; the
        // wildcard-subdomain entitlement this pairs with already requires iOS 14+.
        detail.put("components", List.of(Map.of("/", "*", "comment", "Open every path in the app")));

        Map<String, Object> applinks = new LinkedHashMap<>();
        applinks.put("apps", List.of()); // required-empty in the legacy schema; harmless for new parsers
        applinks.put("details", List.of(detail));

        return ResponseEntity.ok().body(Map.of("applinks", applinks));
    }

    /**
     * Android {@code assetlinks.json}. One {@code handle_all_urls} statement per configured app,
     * binding its package + signing-cert fingerprint(s) to this domain.
     */
    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        List<AppLinksProperties.AndroidApp> apps = properties.getAndroid();
        if (apps == null || apps.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> statements = new ArrayList<>();
        for (AppLinksProperties.AndroidApp app : apps) {
            if (app.getPackageName() == null || app.getPackageName().isBlank()
                    || app.getSha256CertFingerprints() == null || app.getSha256CertFingerprints().isEmpty()) {
                continue; // an entry missing its package or fingerprints can't verify — skip it
            }
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("namespace", "android_app");
            target.put("package_name", app.getPackageName());
            target.put("sha256_cert_fingerprints", new ArrayList<>(app.getSha256CertFingerprints()));

            Map<String, Object> statement = new LinkedHashMap<>();
            statement.put("relation", List.of("delegate_permission/common.handle_all_urls"));
            statement.put("target", target);
            statements.add(statement);
        }
        if (statements.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().body(statements);
    }
}
