package com.onec.hospedajes;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Low-level SOAP transport: posts a prepared envelope to the Comunicación endpoint over mutual TLS
 * with HTTP Basic credentials, and returns the raw response body.
 */
public class HospedajesTransport {

    private final HttpClient httpClient;
    private final HospedajesProperties properties;
    private final String basicAuth;

    public HospedajesTransport(HospedajesProperties properties, SSLContext sslContext) {
        this.properties = properties;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
        String token = (properties.getUsername() == null ? "" : properties.getUsername())
                + ":" + (properties.getPassword() == null ? "" : properties.getPassword());
        this.basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    public String post(String soapEnvelope) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.resolveEndpoint()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("Authorization", basicAuth)
                .header("SOAPAction", "")
                .POST(HttpRequest.BodyPublishers.ofString(soapEnvelope, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new HospedajesException(
                        "SES.HOSPEDAJES returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (HospedajesException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HospedajesException("Interrupted while calling SES.HOSPEDAJES", e);
        } catch (Exception e) {
            throw new HospedajesException("Failed to call SES.HOSPEDAJES", e);
        }
    }
}
