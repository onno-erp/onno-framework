package su.onno.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpaIndexHtmlTest {

    @TempDir
    Path temp;

    @Test
    void injectsBasePathScriptWithoutAPlaceholder() throws IOException {
        var index = new SpaIndexHtml("/erp/", List.of(html("<html><head><title>App</title></head></html>")));

        assertThat(read(index.resource())).isEqualTo(
                "<html><head><script>window.__onnoBasePath = \"/erp\";</script><title>App</title></head></html>");
    }

    @Test
    void replacesTheLegacyPlaceholderToo() throws IOException {
        var index = new SpaIndexHtml("/ui", List.of(html(
                "<head lang=\"en\"><script>window.__onnoBasePath = \"__ONNO_BASE_PATH__\";</script></head>")));

        String served = read(index.resource());
        assertThat(served).doesNotContain(SpaIndexHtml.PLACEHOLDER);
        assertThat(served).isEqualTo("<head lang=\"en\"><script>window.__onnoBasePath = \"/ui\";</script>"
                + "<script>window.__onnoBasePath = \"/ui\";</script></head>");
    }

    @Test
    void escapesTheBasePathForAnInlineScript() throws IOException {
        var index = new SpaIndexHtml("/a\"</script><x>&'\\", List.of(html("<head></head>")));

        String served = read(index.resource());
        assertThat(served).isEqualTo("<head><script>window.__onnoBasePath = "
                + "\"/a\\u0022\\u003c/script\\u003e\\u003cx\\u003e\\u0026\\u0027\\u005c\";</script></head>");
        assertThat(served.indexOf("</script>")).isEqualTo(served.lastIndexOf("</script>"));
    }

    @Test
    void prefersTheApplicationBuildOverTheDefaultShell(@TempDir Path root) throws IOException {
        write(root.resolve("onno-app/index.html"), "<head><title>app</title></head>");
        write(root.resolve("static/ui/index.html"), "<head><title>shell</title></head>");
        write(root.resolve("only-shell/static/ui/index.html"), "<head><title>shell</title></head>");

        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null);
             var shellOnly = new URLClassLoader(new URL[]{root.resolve("only-shell").toUri().toURL()}, null)) {
            assertThat(read(new SpaIndexHtml("/", candidates(loader)).resource())).contains("<title>app</title>");
            assertThat(read(new SpaIndexHtml("/", candidates(shellOnly)).resource())).contains("<title>shell</title>");
        }
    }

    @Test
    void noShellOnTheClasspathResolvesToNull() {
        assertThat(new SpaIndexHtml("/ui", List.of(new ClassPathResource("missing/index.html"))).resource())
                .isNull();
    }

    @Test
    void injectsAfterAHeadTagWithAttributes() {
        String markup = "<script>x</script>";
        assertThat(SpaIndexHtml.injectIntoHead("<html><head lang=\"en\" data-x=\"1\"><title>x</title></head></html>", markup))
                .isEqualTo("<html><head lang=\"en\" data-x=\"1\">" + markup + "<title>x</title></head></html>");
        assertThat(SpaIndexHtml.injectIntoHead("<HTML><HEAD><title>x</title></HEAD>", markup))
                .isEqualTo("<HTML><HEAD>" + markup + "<title>x</title></HEAD>");
        // <header> in the body is not a head tag; only the first real head receives the markup.
        assertThat(SpaIndexHtml.injectIntoHead("<head></head><body><header></header></body>", markup))
                .isEqualTo("<head>" + markup + "</head><body><header></header></body>");
        assertThat(SpaIndexHtml.injectIntoHead("<body></body>", markup)).isEqualTo("<body></body>");
    }

    private static List<Resource> candidates(ClassLoader loader) {
        return List.of(
                new ClassPathResource(SpaIndexHtml.APP_LOCATION + "index.html", loader),
                new ClassPathResource(SpaIndexHtml.DEFAULT_LOCATION + "index.html", loader));
    }

    private Resource html(String content) throws IOException {
        Path file = Files.createTempFile(temp, "index", ".html");
        Files.writeString(file, content);
        return new FileSystemResource(file);
    }

    private static String read(Resource resource) throws IOException {
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
