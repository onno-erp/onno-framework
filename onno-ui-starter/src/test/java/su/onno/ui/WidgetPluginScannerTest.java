package su.onno.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetPluginScannerTest {

    @Test
    void discoversPluginModulesOnTheClasspath() {
        // src/test/resources/onno-plugins/TestWidget.js is on the test classpath.
        WidgetPluginScanner scanner = new WidgetPluginScanner("classpath*:/onno-plugins/");
        assertThat(scanner.scriptNames()).contains("TestWidget.js");
        // Stylesheets are discovered separately — scriptNames stays JS-only.
        assertThat(scanner.scriptNames()).doesNotContain("onno-widgets.css");
    }

    @Test
    void discoversPluginStylesheetsOnTheClasspath() {
        // src/test/resources/onno-plugins/onno-widgets.css is on the test classpath.
        WidgetPluginScanner scanner = new WidgetPluginScanner("classpath*:/onno-plugins/");
        assertThat(scanner.styleNames()).contains("onno-widgets.css");
        assertThat(scanner.styleNames()).doesNotContain("TestWidget.js");
    }

    @Test
    void stagesPluginsAtAStableExplodedServeLocation() throws Exception {
        WidgetPluginScanner scanner = new WidgetPluginScanner("classpath*:/onno-plugins/");
        assertThat(scanner.serveLocation()).startsWith("file:");
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(
                java.net.URI.create(scanner.serveLocation())).resolve("TestWidget.js")))
                .contains("registerWidget");
        scanner.close();
    }

    @Test
    void missingLocationYieldsNoScriptsRatherThanFailing() {
        WidgetPluginScanner scanner = new WidgetPluginScanner("classpath*:/no-such-plugins-dir/");
        assertThat(scanner.scriptNames()).isEmpty();
        // A trailing slash is always appended so the resource handler gets a directory location.
        assertThat(scanner.serveLocation()).isEqualTo("classpath:/no-such-plugins-dir/");
    }
}
