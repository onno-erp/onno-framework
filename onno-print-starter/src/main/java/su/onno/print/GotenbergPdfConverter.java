package su.onno.print;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Converts through a <a href="https://gotenberg.dev">Gotenberg</a> service (a stateless HTTP API
 * around LibreOffice): {@code POST {url}/forms/libreoffice/convert} with the file as multipart data.
 * Run it next to the app, e.g. {@code docker run -p 3000:3000 gotenberg/gotenberg:8}.
 */
public class GotenbergPdfConverter implements PdfConverter {

    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    public GotenbergPdfConverter(URI baseUrl, Duration timeout) {
        String base = baseUrl.toString().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/forms/libreoffice/convert");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public byte[] toPdf(byte[] document, String format) {
        String boundary = "onno-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"document." + format + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(document);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Gotenberg conversion failed with HTTP " + response.statusCode()
                        + ": " + new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reach Gotenberg at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while converting to PDF", e);
        }
    }
}
