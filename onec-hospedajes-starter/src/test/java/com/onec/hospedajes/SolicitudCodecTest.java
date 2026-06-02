package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Contrato;
import com.onec.hospedajes.model.Direccion;
import com.onec.hospedajes.model.Pago;
import com.onec.hospedajes.model.Persona;
import com.onec.hospedajes.model.Peticion;
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
    void marshalsParteToTheAltaParteHospedajeSchema() {
        String xml = codec.toXml(samplePeticion());

        // Root is the altaParteHospedaje peticion; establishment is carried once on the solicitud.
        assertThat(xml).contains("http://www.neg.hospedajes.mir.es/altaParteHospedaje");
        assertThat(xml).contains("peticion");
        assertThat(xml).contains("<codigoEstablecimiento>EST-001</codigoEstablecimiento>");
        assertThat(xml).contains("<referencia>B-000001</referencia>");
        assertThat(xml).contains("<fechaContrato>2026-05-30</fechaContrato>");
        assertThat(xml).contains("<fechaEntrada>2026-06-01T14:00:00</fechaEntrada>");
        assertThat(xml).contains("<fechaSalida>2026-06-05T11:00:00</fechaSalida>");
        assertThat(xml).contains("<tipoPago>EFECT</tipoPago>");
        assertThat(xml).contains("<rol>VI</rol>");
        assertThat(xml).contains("<apellido1>García</apellido1>");
        assertThat(xml).contains("<nacionalidad>ESP</nacionalidad>");
        assertThat(xml).contains("<codigoPostal>28013</codigoPostal>");
        assertThat(xml).contains("<pais>ESP</pais>");
    }

    @Test
    void encodesAsZippedBase64ThatRoundTripsBackToTheXml() throws Exception {
        String xml = codec.toXml(samplePeticion());
        String encoded = codec.encode(xml);

        byte[] zipped = Base64.getDecoder().decode(encoded);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            assertThat(zip.getNextEntry()).isNotNull();
            String unzipped = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(unzipped).isEqualTo(xml);
        }
    }

    private Peticion samplePeticion() {
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

        Pago pago = new Pago();
        pago.setTipoPago("EFECT");

        Contrato contrato = new Contrato();
        contrato.setReferencia("B-000001");
        contrato.setFechaContrato(LocalDate.of(2026, 5, 30));
        contrato.setFechaEntrada(LocalDateTime.of(2026, 6, 1, 14, 0, 0));
        contrato.setFechaSalida(LocalDateTime.of(2026, 6, 5, 11, 0, 0));
        contrato.setNumPersonas(1);
        contrato.setPago(pago);

        Comunicacion comunicacion = new Comunicacion();
        comunicacion.setContrato(contrato);
        comunicacion.setPersona(List.of(persona));

        Solicitud solicitud = new Solicitud();
        solicitud.setCodigoEstablecimiento("EST-001");
        solicitud.setComunicacion(List.of(comunicacion));
        return new Peticion(solicitud);
    }
}
