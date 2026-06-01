package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Contrato;
import com.onec.hospedajes.model.Direccion;
import com.onec.hospedajes.model.Persona;

import com.sun.net.httpserver.HttpServer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full client + transport + service + log flow against a stubbed SOAP endpoint over
 * plain HTTP (so no certificates or real credentials are needed). The stub responses mirror the
 * shape the parser expects; the goal is to prove the end-to-end plumbing — envelope construction,
 * Basic-auth header, ZIP+Base64 payload, response parsing, and the SUBMITTED→REGISTERED lifecycle.
 */
class HospedajesServiceIntegrationTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    private HospedajesService service;
    private HospedajesCommunicationLog log;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ws/v1/comunicacion", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                lastRequestBody.set(body);
                lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String response = body.contains("consultaLoteRequest")
                        ? consultaLoteResponse()
                        : comunicacionResponse();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        server.start();

        HospedajesProperties props = new HospedajesProperties();
        props.setEnabled(true);
        props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/ws/v1/comunicacion");
        props.setArrendador("0000000001");
        props.setAplicacion("onec-test");
        props.setUsername("ws-user");
        props.setPassword("ws-pass");

        HospedajesTransport transport = new HospedajesTransport(props, null);
        HospedajesClient client = new DefaultHospedajesClient(transport, new SolicitudCodec(), props);
        log = new HospedajesCommunicationLog(Jdbi.create("jdbc:h2:mem:hosp;DB_CLOSE_DELAY=-1"));
        log.initSchema();
        service = new HospedajesService(client, log);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void submitsAltaWithBasicAuthAndRecordsTheBatch() {
        ComunicacionResult result = service.registrar(List.of(sampleComunicacion()));

        assertThat(result.accepted()).isTrue();
        assertThat(result.numeroLote()).isEqualTo("LOTE-123");

        // Basic auth header is base64(user:pass)
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("ws-user:ws-pass".getBytes(StandardCharsets.UTF_8));
        assertThat(lastAuthHeader.get()).isEqualTo(expected);

        // Envelope carries the header fields and a (zipped/base64) solicitud
        assertThat(lastRequestBody.get()).contains("<codigoArrendador>0000000001</codigoArrendador>");
        assertThat(lastRequestBody.get()).contains("<tipoOperacion>A</tipoOperacion>");
        assertThat(lastRequestBody.get()).contains("<tipoComunicacion>PV</tipoComunicacion>");
        assertThat(lastRequestBody.get()).contains("<solicitud>");
    }

    @Test
    void reconcileMarksCommunicationRegisteredFromConsultaLote() {
        service.registrar(List.of(sampleComunicacion()));

        int updated = service.reconcile(10);

        assertThat(updated).isEqualTo(1);
        assertThat(log.findUnreconciledLotes(10)).isEmpty();
    }

    private String comunicacionResponse() {
        return "<?xml version=\"1.0\"?><comunicacionResponse>"
                + "<resultado><codigo>0</codigo><descripcion>OK</descripcion></resultado>"
                + "<numeroLote>LOTE-123</numeroLote></comunicacionResponse>";
    }

    private String consultaLoteResponse() {
        return "<?xml version=\"1.0\"?><consultaLoteResponse>"
                + "<resultado><codigo>0</codigo><descripcion>OK</descripcion></resultado>"
                + "<numeroLote>LOTE-123</numeroLote>"
                + "<comunicacion><referencia>B-000001</referencia>"
                + "<codigoComunicacion>PV-ABC-001</codigoComunicacion></comunicacion>"
                + "</consultaLoteResponse>";
    }

    private Comunicacion sampleComunicacion() {
        Direccion direccion = new Direccion();
        direccion.setDireccion("Calle Mayor 1");
        direccion.setCodigoMunicipio("28079");
        direccion.setCodigoPostal("28013");
        direccion.setPais("ESP");

        Persona persona = new Persona();
        persona.setNombre("Ana");
        persona.setApellido1("García");
        persona.setApellido2("López");
        persona.setTipoDocumento("NIF");
        persona.setNumeroDocumento("12345678Z");
        persona.setFechaNacimiento(LocalDate.of(1990, 1, 2));
        persona.setNacionalidad("ESP");
        persona.setDireccion(direccion);
        persona.setCorreo("ana@example.com");

        Contrato contrato = new Contrato();
        contrato.setReferencia("B-000001");
        contrato.setFechaContrato(LocalDate.of(2026, 5, 30));
        contrato.setFechaEntrada(LocalDateTime.of(2026, 6, 1, 14, 0, 0));
        contrato.setFechaSalida(LocalDate.of(2026, 6, 5));
        contrato.setNumPersonas(1);

        Comunicacion comunicacion = new Comunicacion();
        comunicacion.setCodigoEstablecimiento("EST-001");
        comunicacion.setContrato(contrato);
        comunicacion.setPersona(List.of(persona));
        return comunicacion;
    }
}
