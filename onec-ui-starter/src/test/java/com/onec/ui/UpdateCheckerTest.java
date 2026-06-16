package com.onec.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two contracts: the version comparison only fires on a genuinely newer release (and never on a
 * SNAPSHOT or unparseable string), and a single poll maps each cloud response — newer, 204, error —
 * to the right {@link UpdateStatus} without ever throwing.
 */
class UpdateCheckerTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ----- version comparison -----

    @Test
    void newerVersionsAreDetected() {
        assertThat(UpdateChecker.isNewer("0.11.0", "0.10.0")).isTrue();
        assertThat(UpdateChecker.isNewer("0.10.1", "0.10.0")).isTrue();
        assertThat(UpdateChecker.isNewer("1.0.0", "0.99.99")).isTrue();
        assertThat(UpdateChecker.isNewer("0.10.1", "0.10")).isTrue();      // more segments
        assertThat(UpdateChecker.isNewer("0.10.0", "0.10.0-SNAPSHOT")).isTrue(); // release > pre-release
    }

    @Test
    void sameOrOlderOrUnparseableDoesNotSignal() {
        assertThat(UpdateChecker.isNewer("0.10.0", "0.10.0")).isFalse();
        assertThat(UpdateChecker.isNewer("0.9.0", "0.10.0")).isFalse();    // 9 < 10, not lexical
        assertThat(UpdateChecker.isNewer("0.10.0-SNAPSHOT", "0.10.0")).isFalse(); // snapshot !> release
        assertThat(UpdateChecker.isNewer("garbage", "0.10.0")).isFalse();
        assertThat(UpdateChecker.isNewer("0.11.0", "")).isFalse();         // unknown local version
        assertThat(UpdateChecker.isNewer(null, "0.10.0")).isFalse();
    }

    // ----- one poll against a stub cloud -----

    private UpdateChecker checkerFor(int status, String body, String currentVersion) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/v1/latest", exchange -> {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        UpdateProperties props = new UpdateProperties();
        props.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/releases/v1/latest");
        return new UpdateChecker(props, currentVersion, new ObjectMapper());
    }

    @Test
    void aNewerReleaseFlipsTheStatus() throws Exception {
        UpdateChecker checker = checkerFor(200,
                "{\"version\":\"0.11.0\",\"notesUrl\":\"https://onno.su/r/0.11.0\"}", "0.10.0");
        checker.check();

        UpdateStatus s = checker.status();
        assertThat(s.updateAvailable()).isTrue();
        assertThat(s.latestVersion()).isEqualTo("0.11.0");
        assertThat(s.currentVersion()).isEqualTo("0.10.0");
        assertThat(s.releaseUrl()).isEqualTo("https://onno.su/r/0.11.0");
        assertThat(s.checkedAt()).isNotNull();
    }

    @Test
    void sameVersionReportsNoUpdate() throws Exception {
        UpdateChecker checker = checkerFor(200, "{\"version\":\"0.10.0\"}", "0.10.0");
        checker.check();

        assertThat(checker.status().updateAvailable()).isFalse();
        assertThat(checker.status().latestVersion()).isEqualTo("0.10.0");
    }

    @Test
    void noContentClearsToNoUpdate() throws Exception {
        UpdateChecker checker = checkerFor(204, null, "0.10.0");
        checker.check();

        assertThat(checker.status().updateAvailable()).isFalse();
        assertThat(checker.status().latestVersion()).isNull();
    }

    @Test
    void serverErrorLeavesStatusUntouchedAndNeverThrows() throws Exception {
        UpdateChecker checker = checkerFor(500, "boom", "0.10.0");
        checker.check();   // must not throw

        assertThat(checker.status().updateAvailable()).isFalse();
        assertThat(checker.status().latestVersion()).isNull();
    }
}
