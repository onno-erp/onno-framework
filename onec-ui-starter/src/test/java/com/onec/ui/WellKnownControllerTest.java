package com.onec.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two app-association files: 404 until configured, and once configured a payload shaped exactly
 * as iOS / Android expect (so the OS verifies the association and routes links to the app).
 */
class WellKnownControllerTest {

    @Test
    void unconfiguredEndpointsReturn404() {
        WellKnownController controller = new WellKnownController(new AppLinksProperties());

        assertThat(controller.appleAppSiteAssociation().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.assetLinks().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appleAppSiteAssociationCarriesConfiguredAppIds() {
        AppLinksProperties props = new AppLinksProperties();
        props.setAppleAppIds(List.of("ABCDE12345.su.onno.onec.mobile"));

        ResponseEntity<Map<String, Object>> response = new WellKnownController(props).appleAppSiteAssociation();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> applinks = (Map<String, Object>) response.getBody().get("applinks");
        List<Map<String, Object>> details = (List<Map<String, Object>>) applinks.get("details");
        assertThat(details).hasSize(1);
        assertThat((List<String>) details.get(0).get("appIDs"))
                .containsExactly("ABCDE12345.su.onno.onec.mobile");
        // Matches every path on the domain.
        List<Map<String, Object>> components = (List<Map<String, Object>>) details.get(0).get("components");
        assertThat(components.get(0).get("/")).isEqualTo("*");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assetLinksCarriesPackageAndFingerprints() {
        AppLinksProperties.AndroidApp app = new AppLinksProperties.AndroidApp();
        app.setPackageName("su.onno.onec.mobile");
        app.setSha256CertFingerprints(List.of("AB:CD:EF:00:11:22"));
        AppLinksProperties props = new AppLinksProperties();
        props.setAndroid(List.of(app));

        ResponseEntity<List<Map<String, Object>>> response = new WellKnownController(props).assetLinks();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);

        Map<String, Object> statement = response.getBody().get(0);
        assertThat((List<String>) statement.get("relation"))
                .containsExactly("delegate_permission/common.handle_all_urls");
        Map<String, Object> target = (Map<String, Object>) statement.get("target");
        assertThat(target.get("namespace")).isEqualTo("android_app");
        assertThat(target.get("package_name")).isEqualTo("su.onno.onec.mobile");
        assertThat((List<String>) target.get("sha256_cert_fingerprints"))
                .containsExactly("AB:CD:EF:00:11:22");
    }

    @Test
    void androidEntryMissingFingerprintsIsSkipped() {
        AppLinksProperties.AndroidApp app = new AppLinksProperties.AndroidApp();
        app.setPackageName("su.onno.onec.mobile"); // no fingerprints → can't verify
        AppLinksProperties props = new AppLinksProperties();
        props.setAndroid(List.of(app));

        assertThat(new WellKnownController(props).assetLinks().getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
