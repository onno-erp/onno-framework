package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Contrato;
import com.onec.hospedajes.model.Direccion;
import com.onec.hospedajes.model.Persona;
import com.onec.hospedajes.model.Solicitud;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SolicitudCodecTest {

    private final SolicitudCodec codec = new SolicitudCodec();

    @Test
    void marshalsPartesViajerosToExpectedXml() {
        String xml = codec.toXml(sampleSolicitud());

        assertThat(xml).contains("<solicitud>");
        assertThat(xml).contains("<codigoEstablecimiento>EST-001</codigoEstablecimiento>");
        assertThat(xml).contains("<referencia>B-000001</referencia>");
        assertThat(xml).contains("<fechaContrato>2026-05-30</fechaContrato>");
        assertThat(xml).contains("<fechaEntrada>2026-06-01T14:00:00</fechaEntrada>");
        assertThat(xml).contains("<rol>VI</rol>");
        assertThat(xml).contains("<apellido1>García</apellido1>");
        assertThat(xml).contains("<nacionalidad>ESP</nacionalidad>");
        assertThat(xml).contains("<pais>ESP</pais>");
    }

    @Test
    void encodesAsZippedBase64ThatRoundTripsBackToTheXml() throws Exception {
        String xml = codec.toXml(sampleSolicitud());
        String encoded = codec.encode(xml);

        byte[] zipped = Base64.getDecoder().decode(encoded);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            assertThat(zip.getNextEntry()).isNotNull();
            String unzipped = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(unzipped).isEqualTo(xml);
        }
    }

    private Solicitud sampleSolicitud() {
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
        persona.setSexo("M");
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

        Solicitud solicitud = new Solicitud();
        solicitud.setComunicacion(List.of(comunicacion));
        return solicitud;
    }
}
