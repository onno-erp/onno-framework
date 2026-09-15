package su.onno.crm.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import su.onno.crm.channels.telegram.TelegramClient;

/**
 * The bytes that actually leave for a provider.
 *
 * <p>Both paths are hand-built wire formats — a multipart upload and a MIME message — so they are
 * asserted against what a provider would parse, not against the code that wrote them.
 */
class OutboundAttachmentTest {

    private static final byte[] PDF = "%PDF-1.7 quote".getBytes(StandardCharsets.UTF_8);

    /** GmailMail is package-private in the gmail package; the MIME it produces is the contract. */
    private static String gmailReply(String body, List<Object> files) throws Exception {
        Class<?> mail = Class.forName("su.onno.crm.channels.gmail.GmailMail");
        Method reply = mail.getDeclaredMethod("reply", String.class, String.class, String.class,
                String.class, String.class, List.class, UUID.class);
        reply.setAccessible(true);
        return (String) reply.invoke(null, "agent@example.com", "client@example.com", "Your quote",
                "", body, files, UUID.randomUUID());
    }

    private static Object gmailFile(String name, String type, byte[] content) throws Exception {
        Class<?> outgoing = Class.forName("su.onno.crm.channels.gmail.GmailMail$Outgoing");
        Constructor<?> constructor = outgoing.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(name, type, content);
    }

    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
    }

    @Test
    void gmailReplyWithFilesIsMultipartWithTheNoteFirstAndTheFileNamedAndIntact() throws Exception {
        var message = parse(gmailReply("The quote, as promised.",
                List.of(gmailFile("Villa Balbiano quote.pdf", "application/pdf", PDF))));

        assertThat(message.isMimeType("multipart/mixed")).isTrue();
        var parts = (MimeMultipart) message.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(0).getContent()).isEqualTo("The quote, as promised.");

        var attachment = parts.getBodyPart(1);
        assertThat(attachment.getDisposition()).isEqualToIgnoringCase(Part.ATTACHMENT);
        assertThat(attachment.getFileName()).isEqualTo("Villa Balbiano quote.pdf");
        assertThat(attachment.getInputStream().readAllBytes()).isEqualTo(PDF);
    }

    /** A filename is quoted into a header; a newline in one would forge the headers after it. */
    @Test
    void gmailAttachmentNameCannotBreakOutOfItsHeader() throws Exception {
        var message = parse(gmailReply("See attached",
                List.of(gmailFile("quote\r\nBcc: leak@example.com", "application/pdf", PDF))));

        var attachment = ((MimeMultipart) message.getContent()).getBodyPart(1);
        assertThat(attachment.getFileName()).doesNotContain("\r").doesNotContain("\n");
        assertThat(message.getHeader("Bcc")).isNull();
    }

    /** With nothing attached the reply stays exactly the plain-text message it always was. */
    @Test
    void gmailReplyWithoutFilesStaysPlainText() throws Exception {
        var message = parse(gmailReply("Just a note.", List.of()));

        assertThat(message.isMimeType("text/plain")).isTrue();
        assertThat(message.getContent()).isEqualTo("Just a note.");
    }

    /** A local stand-in for api.telegram.org: it records the one request the client makes. */
    private record Captured(String path, String contentType, byte[] body) {}

    private static Captured telegramCall(java.util.function.Consumer<TelegramClient> call) throws Exception {
        var captured = new AtomicReference<Captured>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new Captured(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestBody().readAllBytes()));
            byte[] ok = "{\"ok\":true,\"result\":{\"message_id\":42}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (var out = exchange.getResponseBody()) { out.write(ok); }
        });
        server.start();
        try {
            Constructor<TelegramClient> constructor = TelegramClient.class
                    .getDeclaredConstructor(String.class, String.class, ObjectMapper.class);
            constructor.setAccessible(true);
            call.accept(constructor.newInstance(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", new ObjectMapper()));
        } finally {
            server.stop(0);
        }
        return captured.get();
    }

    @Test
    void telegramSendsADocumentAsMultipartCarryingTheNameTypeAndBytes() throws Exception {
        var request = telegramCall(client ->
                assertThat(client.sendFile(77L, "quote.pdf", "application/pdf", PDF, "Here it is")).isEqualTo(42L));

        assertThat(request.path()).endsWith("/sendDocument");
        assertThat(request.contentType()).startsWith("multipart/form-data; boundary=");
        var body = new String(request.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("name=\"chat_id\"").contains("77")
                .contains("name=\"caption\"").contains("Here it is")
                .contains("name=\"document\"; filename=\"quote.pdf\"")
                .contains("Content-Type: application/pdf")
                .contains("%PDF-1.7 quote");
        // The trailing boundary is what tells the server the body is complete.
        String boundary = request.contentType().substring(request.contentType().indexOf("boundary=") + 9);
        assertThat(body).endsWith("--" + boundary + "--\r\n");
    }

    /** An image is sent as a photo so it renders in the chat rather than arriving as a file. */
    @Test
    void telegramSendsAnImageAsAPhoto() throws Exception {
        var request = telegramCall(client -> client.sendFile(77L, "venue.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G'}, null));

        assertThat(request.path()).endsWith("/sendPhoto");
        var body = new String(request.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("name=\"photo\"; filename=\"venue.png\"").doesNotContain("name=\"caption\"");
    }

    @Test
    void telegramRefusesAFileLargerThanItAcceptsWithoutCallingTheApi() throws Exception {
        var request = telegramCall(client -> assertThatThrownBy(() ->
                client.sendFile(77L, "huge.bin", "application/octet-stream",
                        new byte[TelegramClient.UPLOAD_LIMIT + 1], null))
                .hasMessageContaining("larger than Telegram accepts"));

        assertThat(request).isNull();
    }
}
