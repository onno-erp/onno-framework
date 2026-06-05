package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Contrato;
import com.onec.hospedajes.model.Direccion;
import com.onec.hospedajes.model.Pago;
import com.onec.hospedajes.model.Persona;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-submission validator for a parte de viajeros (PV).
 *
 * <p>Encodes the conditional ("Obligatorio si …") business rules from the SES.HOSPEDAJES interface
 * spec (MIR-HOSPE-DSI-WS — Comunicaciones v3.1.3, §3.1.1.1 <em>Alta de partes de viajeros</em> and
 * §4.1 <em>Bloque dirección</em>) that the XSD does <strong>not</strong> express. Because they are
 * cross-field rules, a malformed parte passes XSD validation but is rejected by the service
 * asynchronously with error {@code 10121 ("Error de validación")} — surfaced only later, via
 * {@code consultaLote}, with an opaque message.
 *
 * <p>Running these checks before submission turns that delayed, opaque batch rejection into an
 * immediate, precise reason and stops malformed partes from ever reaching the service. The reported
 * municipality error — <em>"Código de municipio obligatorio si el país es España"</em> — is one of
 * them ({@link #SPAIN} ⇒ {@code codigoMunicipio} required).
 */
public final class ParteValidator {

    /** ISO 3166-1 alpha-3 code for Spain — the country that makes {@code codigoMunicipio} mandatory. */
    public static final String SPAIN = "ESP";

    /** Document types that require a {@code soporteDocumento} (número de soporte). */
    private static final String NIF = "NIF";
    private static final String NIE = "NIE";

    private static final int ADULT_AGE = 18;

    /**
     * Validate a parte de viajeros against the MIR conditional rules.
     *
     * @return one human-readable message per violated rule, in document order; empty when valid.
     */
    public List<String> validate(Comunicacion comunicacion) {
        List<String> violations = new ArrayList<>();
        if (comunicacion == null) {
            violations.add("comunicacion is required");
            return violations;
        }

        Contrato contrato = comunicacion.getContrato();
        if (contrato == null) {
            violations.add("contrato is required");
        } else {
            Pago pago = contrato.getPago();
            if (pago == null || isBlank(pago.getTipoPago())) {
                violations.add("contrato.pago.tipoPago is required");
            }
        }

        List<Persona> personas = comunicacion.getPersona();
        if (personas == null || personas.isEmpty()) {
            violations.add("at least one persona (traveler) is required");
            return violations;
        }

        // "Mayor de edad" is judged at the date of stay (entrada), falling back to the contract date.
        LocalDate referenceDate = stayDate(contrato);
        boolean hasMinor = false;
        boolean anyAdultHasParentesco = false;
        for (int i = 0; i < personas.size(); i++) {
            Persona persona = personas.get(i);
            String who = personLabel(i, persona);
            boolean adult = validatePersona(persona, referenceDate, who, violations);
            if (!adult && persona.getFechaNacimiento() != null) {
                hasMinor = true;
            }
            if (adult && !isBlank(persona.getParentesco())) {
                anyAdultHasParentesco = true;
            }
        }

        // §3.1.1.1: when a minor is present, at least one adult must declare the relationship
        // (parentesco) with that minor.
        if (hasMinor && !anyAdultHasParentesco) {
            violations.add("parentesco is required on at least one adult when a minor traveler is present");
        }

        return violations;
    }

    /** @return {@code true} if the traveler is an adult (so adult-only requirements were checked). */
    private boolean validatePersona(Persona persona, LocalDate referenceDate, String who,
                                    List<String> violations) {
        if (isBlank(persona.getNombre())) {
            violations.add(who + ": nombre is required");
        }
        if (isBlank(persona.getApellido1())) {
            violations.add(who + ": apellido1 is required");
        }
        if (persona.getFechaNacimiento() == null) {
            violations.add(who + ": fechaNacimiento is required");
        }

        boolean adult = isAdult(persona.getFechaNacimiento(), referenceDate);
        String tipoDocumento = trimToNull(persona.getTipoDocumento());
        if (adult) {
            if (tipoDocumento == null) {
                violations.add(who + ": tipoDocumento is required for adults");
            }
            if (isBlank(persona.getNumeroDocumento())) {
                violations.add(who + ": numeroDocumento is required for adults");
            }
        }

        // §3.1.1.1: apellido2 is mandatory when the document is a NIF; soporteDocumento when NIF or NIE.
        if (NIF.equalsIgnoreCase(tipoDocumento) && isBlank(persona.getApellido2())) {
            violations.add(who + ": apellido2 is required when tipoDocumento is NIF");
        }
        if ((NIF.equalsIgnoreCase(tipoDocumento) || NIE.equalsIgnoreCase(tipoDocumento))
                && isBlank(persona.getSoporteDocumento())) {
            violations.add(who + ": soporteDocumento is required when tipoDocumento is NIF or NIE");
        }

        // §3.1.1.1: at least one of telefono / telefono2 / correo must be present.
        if (isBlank(persona.getTelefono()) && isBlank(persona.getTelefono2())
                && isBlank(persona.getCorreo())) {
            violations.add(who + ": at least one of telefono, telefono2 or correo is required");
        }

        if (persona.getDireccion() == null) {
            violations.add(who + ": direccion is required");
        } else {
            validateDireccion(persona.getDireccion(), who, violations);
        }
        return adult;
    }

    /** Validate the §4.1 dirección block, including the España ⇄ municipio cross-field rule. */
    private void validateDireccion(Direccion direccion, String who, List<String> violations) {
        if (isBlank(direccion.getDireccion())) {
            violations.add(who + ": direccion.direccion (street) is required");
        }
        if (isBlank(direccion.getCodigoPostal())) {
            violations.add(who + ": direccion.codigoPostal is required");
        }
        String pais = trimToNull(direccion.getPais());
        if (pais == null) {
            violations.add(who + ": direccion.pais is required");
            return;
        }
        if (SPAIN.equalsIgnoreCase(pais)) {
            // The reported error: codigoMunicipio is mandatory (and must be the 5-digit INE code).
            String codigo = trimToNull(direccion.getCodigoMunicipio());
            if (codigo == null) {
                violations.add(who + ": direccion.codigoMunicipio (INE) is required when pais is ESP (Spain)");
            } else if (!codigo.matches("[0-9]{5}")) {
                violations.add(who + ": direccion.codigoMunicipio must be the 5-digit INE code");
            }
        } else if (isBlank(direccion.getNombreMunicipio())) {
            violations.add(who + ": direccion.nombreMunicipio is required when pais is not Spain");
        }
    }

    private static LocalDate stayDate(Contrato contrato) {
        if (contrato == null) {
            return LocalDate.now();
        }
        if (contrato.getFechaEntrada() != null) {
            return contrato.getFechaEntrada().toLocalDate();
        }
        return contrato.getFechaContrato() != null ? contrato.getFechaContrato() : LocalDate.now();
    }

    private static boolean isAdult(LocalDate birthday, LocalDate on) {
        if (birthday == null || on == null) {
            return false;
        }
        return Period.between(birthday, on).getYears() >= ADULT_AGE;
    }

    private static String personLabel(int index, Persona persona) {
        String name = (persona.getNombre() == null ? "" : persona.getNombre()).trim();
        return "persona[" + index + "]" + (name.isEmpty() ? "" : " (" + name + ")");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
