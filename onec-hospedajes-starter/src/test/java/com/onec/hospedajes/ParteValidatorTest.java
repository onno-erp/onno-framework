package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Contrato;
import com.onec.hospedajes.model.Direccion;
import com.onec.hospedajes.model.Pago;
import com.onec.hospedajes.model.Persona;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the conditional ("Obligatorio si …") rules of {@link ParteValidator} — the cross-field
 * requirements from MIR-HOSPE-DSI-WS v3.1.3 §3.1.1.1 and §4.1 that the XSD does not express and the
 * service therefore rejects asynchronously with error 10121.
 */
class ParteValidatorTest {

    private final ParteValidator validator = new ParteValidator();

    @Test
    void acceptsAFullyPopulatedSpanishAdult() {
        assertThat(validator.validate(validSpanishAdult())).isEmpty();
    }

    @Test
    void requiresCodigoMunicipioWhenCountryIsSpain() {
        Comunicacion c = validSpanishAdult();
        c.getPersona().get(0).getDireccion().setCodigoMunicipio(null);

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("codigoMunicipio")
                        .contains("ESP"));
    }

    @Test
    void rejectsANonNumericMunicipioCode() {
        Comunicacion c = validSpanishAdult();
        c.getPersona().get(0).getDireccion().setCodigoMunicipio("MADRID");

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("5-digit INE"));
    }

    @Test
    void requiresNombreMunicipioWhenCountryIsNotSpain() {
        Comunicacion c = validSpanishAdult();
        Direccion d = c.getPersona().get(0).getDireccion();
        d.setPais("FRA");
        d.setCodigoMunicipio(null);
        d.setNombreMunicipio(null);

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("nombreMunicipio"));
    }

    @Test
    void acceptsAForeignAddressWithNombreMunicipio() {
        Comunicacion c = validSpanishAdult();
        Persona p = c.getPersona().get(0);
        p.setTipoDocumento("PAS"); // a passport has no NIF/NIE soporte requirement
        p.setSoporteDocumento(null);
        Direccion d = p.getDireccion();
        d.setPais("FRA");
        d.setCodigoMunicipio(null);
        d.setNombreMunicipio("Paris");

        assertThat(validator.validate(c)).isEmpty();
    }

    @Test
    void requiresSoporteDocumentoForNifAndNie() {
        Comunicacion nif = validSpanishAdult();
        nif.getPersona().get(0).setSoporteDocumento(null);
        assertThat(validator.validate(nif))
                .anySatisfy(v -> assertThat(v).contains("soporteDocumento"));

        Comunicacion nie = validSpanishAdult();
        Persona p = nie.getPersona().get(0);
        p.setTipoDocumento("NIE");
        p.setSoporteDocumento(null);
        assertThat(validator.validate(nie))
                .anySatisfy(v -> assertThat(v).contains("soporteDocumento"));
    }

    @Test
    void requiresApellido2ForNif() {
        Comunicacion c = validSpanishAdult();
        c.getPersona().get(0).setApellido2(null);

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("apellido2"));
    }

    @Test
    void requiresDocumentForAdults() {
        Comunicacion c = validSpanishAdult();
        Persona p = c.getPersona().get(0);
        p.setTipoDocumento(null);
        p.setNumeroDocumento(null);
        p.setSoporteDocumento(null);

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("tipoDocumento"))
                .anySatisfy(v -> assertThat(v).contains("numeroDocumento"));
    }

    @Test
    void requiresAtLeastOneContactChannel() {
        Comunicacion c = validSpanishAdult();
        Persona p = c.getPersona().get(0);
        p.setTelefono(null);
        p.setTelefono2(null);
        p.setCorreo(null);

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("telefono"));
    }

    @Test
    void requiresParentescoWhenAMinorIsPresent() {
        Comunicacion c = validSpanishAdult();
        Persona minor = personaLike(c.getPersona().get(0));
        minor.setNombre("Leo");
        minor.setFechaNacimiento(LocalDate.now().minusYears(5));
        // A minor has no document of its own; the adult must declare parentesco.
        minor.setTipoDocumento(null);
        minor.setNumeroDocumento(null);
        minor.setSoporteDocumento(null);
        minor.setApellido2(null);
        c.setPersona(List.of(c.getPersona().get(0), minor));

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("parentesco"));

        // Declaring it on the adult clears the rule.
        c.getPersona().get(0).setParentesco("HIJ");
        assertThat(validator.validate(c)).isEmpty();
    }

    @Test
    void requiresPago() {
        Comunicacion c = validSpanishAdult();
        c.getContrato().setPago(null);

        assertThat(validator.validate(c))
                .anySatisfy(v -> assertThat(v).contains("tipoPago"));
    }

    private static Comunicacion validSpanishAdult() {
        Direccion direccion = new Direccion();
        direccion.setDireccion("Calle Mayor 1");
        direccion.setCodigoMunicipio("28079");
        direccion.setNombreMunicipio("Madrid");
        direccion.setCodigoPostal("28013");
        direccion.setPais("ESP");

        Persona persona = new Persona();
        persona.setRol("VI");
        persona.setNombre("Ana");
        persona.setApellido1("García");
        persona.setApellido2("López");
        persona.setTipoDocumento("NIF");
        persona.setNumeroDocumento("12345678Z");
        persona.setSoporteDocumento("ABC123456");
        persona.setFechaNacimiento(LocalDate.of(1990, 1, 2));
        persona.setNacionalidad("ESP");
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
        comunicacion.setPersona(new java.util.ArrayList<>(List.of(persona)));
        return comunicacion;
    }

    private static Persona personaLike(Persona base) {
        Persona p = new Persona();
        p.setRol("VI");
        p.setNombre(base.getNombre());
        p.setApellido1(base.getApellido1());
        p.setApellido2(base.getApellido2());
        p.setDireccion(base.getDireccion());
        p.setCorreo(base.getCorreo());
        return p;
    }
}
