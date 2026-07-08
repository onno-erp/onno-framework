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
    void resolvesToASingleClasspathServeLocation() {
        WidgetPluginScanner scanner = new WidgetPluginScanner("classpath*:/onno-plugins/");
        assertThat(scanner.serveLocation()).isEqualTo("classpath:/onno-plugins/");
    }

    @Test
    void missingLocationYieldsNoScriptsRatherThanFailing() {
        WidgetPluginScanner scanner = new WidgetPluginScanner("classpath*:/no-such-plugins-dir/");
        assertThat(scanner.scriptNames()).isEmpty();
        // A trailing slash is always appended so the resource handler gets a directory location.
        assertThat(scanner.serveLocation()).isEqualTo("classpath:/no-such-plugins-dir/");
    }
}
