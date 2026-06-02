package com.onec.mail;

import com.onec.mail.dispatch.CompositeMailDispatcher;
import com.onec.mail.dispatch.FileMailDispatcher;
import com.onec.mail.dispatch.MailDispatcher;
import com.onec.mail.template.MailRenderer;
import com.onec.mail.template.MailTemplateDescriptor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailStarterTest {

    public static final class Sample {
        private final String title;
        public Sample(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    @Test
    void rendererInlinesLayoutFragments() {
        MailRenderer renderer = new MailRenderer(new DefaultResourceLoader(), new MailProperties());
        MailTemplateDescriptor descriptor = new MailTemplateDescriptor(
                Sample.class, "t", "Subject [[${doc.title}]]", "classpath:/mail/test-fragment.html", true, "");

        MailRenderer.Rendered rendered = renderer.render(descriptor, new Sample("Hello"), Map.of());

        assertThat(rendered.body()).contains("HEADER").contains("FOOTER").contains("Hello");
        assertThat(rendered.subject()).isEqualTo("Subject Hello");
    }

    @Test
    void htmlToTextStripsMarkupAndKeepsLinks() {
        String html = "<h1>Title</h1><p>Hello <a href=\"https://x.io\">site</a></p><br><div>Bye</div>";

        String text = HtmlToText.convert(html);

        assertThat(text).contains("Title").contains("Hello site (https://x.io)").contains("Bye");
        assertThat(text).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void failoverTriesNextOnFailure() {
        MailDispatcher boom = stub("boom", true);
        MailDispatcher ok = stub("ok", false);
        CompositeMailDispatcher composite = new CompositeMailDispatcher(List.of(boom, ok));

        composite.dispatch(message());

        assertThat(composite.name()).isEqualTo("failover");
    }

    @Test
    void failoverThrowsWhenAllFail() {
        CompositeMailDispatcher composite = new CompositeMailDispatcher(
                List.of(stub("a", true), stub("b", true)));

        assertThatThrownBy(() -> composite.dispatch(message()))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("All failover providers failed");
    }

    @Test
    void fileDispatcherWritesMessageToDisk() throws Exception {
        Path dir = Files.createTempDirectory("mail-test");
        MailProperties props = new MailProperties();
        props.getFile().setDirectory(dir.toString());

        new FileMailDispatcher(props).dispatch(message());

        List<Path> files = Files.list(dir).toList();
        assertThat(files).hasSize(1);
        assertThat(Files.readString(files.get(0))).contains("Subject: Hi").contains("to@x.io");
    }

    private static MailMessage message() {
        return MailMessage.builder().from("from@x.io").to("to@x.io").subject("Hi").html("<p>Body</p>").build();
    }

    private static MailDispatcher stub(String name, boolean fail) {
        return new MailDispatcher() {
            @Override public String name() { return name; }
            @Override public void dispatch(MailMessage message) {
                if (fail) throw new MailDeliveryException(name + " failed");
            }
        };
    }
}
