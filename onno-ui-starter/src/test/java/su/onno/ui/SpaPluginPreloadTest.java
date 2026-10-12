package su.onno.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SpaPluginPreloadTest {
    @Test void advertisesInstalledModulesAtTheConfiguredMount() {
        try (var scanner = new WidgetPluginScanner("classpath*:/onno-plugins/")) {
            var links = SpaIndexHtml.pluginLinks("/erp", scanner);
            assertThat(links).contains("rel=\"modulepreload\" href=\"/erp/plugins/TestWidget.js\" data-onno-plugin-script");
            assertThat(links).contains("data-onno-plugin-style=\"/erp/plugins/onno-widgets.css\"");
            assertThat(SpaIndexHtml.pluginLinks("/", scanner)).contains("href=\"/plugins/TestWidget.js\"");
        }
    }
    @Test void injectsAfterAHeadTagWithAttributes() {
        String links = "<link rel=\"modulepreload\" href=\"/plugins/A.js\">";
        assertThat(SpaIndexHtml.injectIntoHead("<html><head lang=\"en\" data-x=\"1\"><title>x</title></head></html>", links))
                .isEqualTo("<html><head lang=\"en\" data-x=\"1\">" + links + "<title>x</title></head></html>");
        assertThat(SpaIndexHtml.injectIntoHead("<HTML><HEAD><title>x</title></HEAD>", links))
                .isEqualTo("<HTML><HEAD>" + links + "<title>x</title></HEAD>");
        // <header> in the body is not a head tag; only the first real head receives the links.
        assertThat(SpaIndexHtml.injectIntoHead("<head></head><body><header></header></body>", links))
                .isEqualTo("<head>" + links + "</head><body><header></header></body>");
        assertThat(SpaIndexHtml.injectIntoHead("<body></body>", links)).isEqualTo("<body></body>");
    }
    @Test void disabledPluginsEmitNoPreloads() {
        assertThat(SpaIndexHtml.pluginLinks("/ui", null)).isEmpty();
    }
}
