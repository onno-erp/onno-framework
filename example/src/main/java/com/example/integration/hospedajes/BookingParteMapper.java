package com.example.integration.hospedajes;

import com.example.domain.catalogs.Client;
import com.example.domain.catalogs.Country;
import com.example.domain.catalogs.Property;
import com.example.domain.documents.Booking;
import com.example.domain.documents.Guest;
import com.example.domain.enumerations.DocType;
import com.example.domain.enumerations.Gender;
import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Contrato;
import com.onec.hospedajes.model.Direccion;
import com.onec.hospedajes.model.Pago;
import com.onec.hospedajes.model.Persona;
import com.onec.types.Ref;
import com.onec.types.RefResolver;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps a rentals {@link Booking} (and its guests) to a SES.HOSPEDAJES "parte de viajeros"
 * {@link Comunicacion}. Establishment, traveler and address data are resolved from the referenced
 * {@link Property}/{@link Client}/{@link Country} catalogs.
 *
 * <p>Catalog-coded fields (tipoDocumento, sexo, tipoPago) are mapped to the MIR catalogs with
 * best-effort defaults; verify the codes against the live {@code catalogo} service for production.
 */
public class BookingParteMapper {

    /** Conventional local check-in / check-out clock times (the booking only stores dates). */
    private static final LocalTime CHECK_IN_TIME = LocalTime.of(14, 0);
    private static final LocalTime CHECK_OUT_TIME = LocalTime.of(11, 0);

    private final RefResolver refResolver;

    public BookingParteMapper(RefResolver refResolver) {
        this.refResolver = refResolver;
    }

    /** A mappable parte: the establishment it belongs to plus the single comunicación. */
    public record MappedParte(String establishmentCode, Comunicacion comunicacion) {
    }

    /**
     * Build the parte for a booking, or {@link Optional#empty()} when it cannot be registered yet
     * (no property/establishment code, or no identifiable travelers).
     */
    public Optional<MappedParte> toParte(Booking booking) {
        if (booking.getProperty() == null) {
            return Optional.empty();
        }
        Property property = refResolver.resolve(booking.getProperty()).orElse(null);
        if (property == null || isBlank(property.getSesEstablishmentCode())) {
            return Optional.empty();
        }

        List<Persona> personas = travelers(booking);
        if (personas.isEmpty()) {
            return Optional.empty();
        }

        Comunicacion comunicacion = new Comunicacion();
        comunicacion.setContrato(contrato(booking, personas.size()));
        comunicacion.setPersona(personas);
        return Optional.of(new MappedParte(property.getSesEstablishmentCode().trim(), comunicacion));
    }

    private Contrato contrato(Booking booking, int travelerCount) {
        Contrato contrato = new Contrato();
        contrato.setReferencia(booking.getNumber());
        contrato.setFechaContrato(booking.getDate() != null
                ? booking.getDate().toLocalDate() : LocalDate.now());
        if (booking.getCheckIn() != null) {
            contrato.setFechaEntrada(booking.getCheckIn().atTime(CHECK_IN_TIME));
        }
        if (booking.getCheckOut() != null) {
            contrato.setFechaSalida(booking.getCheckOut().atTime(CHECK_OUT_TIME));
        }
        int numPersonas = (booking.getAdults() == null ? 0 : booking.getAdults())
                + (booking.getChildren() == null ? 0 : booking.getChildren());
        contrato.setNumPersonas(numPersonas > 0 ? numPersonas : travelerCount);

        // RD 933/2021 requires a payment block; default to cash when the booking has no detail.
        Pago pago = new Pago();
        pago.setTipoPago("EFECT");
        contrato.setPago(pago);
        return contrato;
    }

    private List<Persona> travelers(Booking booking) {
        List<Persona> personas = new ArrayList<>();
        for (Guest guest : booking.getGuests()) {
            persona(guest.getClient()).ifPresent(personas::add);
        }
        if (personas.isEmpty()) {
            persona(booking.getPrimaryClient()).ifPresent(personas::add);
        }
        return personas;
    }

    private Optional<Persona> persona(Ref<Client> clientRef) {
        if (clientRef == null) {
            return Optional.empty();
        }
        Client client = refResolver.resolve(clientRef).orElse(null);
        if (client == null) {
            return Optional.empty();
        }
        Persona persona = new Persona();
        persona.setRol("VI");
        persona.setNombre(client.getFirstName());
        persona.setApellido1(client.getLastName1());
        persona.setApellido2(client.getLastName2());
        persona.setTipoDocumento(documentoCode(client.getDocType()));
        persona.setNumeroDocumento(client.getDocNumber());
        persona.setFechaNacimiento(client.getBirthday());
        persona.setNacionalidad(countryCode(client.getNationality()));
        persona.setSexo(sexoCode(client.getGender()));
        persona.setCorreo(client.getEmail());
        persona.setTelefono(client.getMobile());
        persona.setDireccion(direccion(client));
        return Optional.of(persona);
    }

    private Direccion direccion(Client client) {
        Direccion direccion = new Direccion();
        direccion.setDireccion(client.getAddress());
        direccion.setNombreMunicipio(client.getCity());
        direccion.setCodigoPostal(client.getPostCode());
        direccion.setPais(countryCode(client.getCountry()));
        return direccion;
    }

    private String countryCode(Ref<Country> countryRef) {
        if (countryRef == null) {
            return null;
        }
        // The Country catalog code is the ISO 3166-1 alpha-3 code the MIR schema expects.
        return refResolver.resolve(countryRef).map(Country::getCode).orElse(null);
    }

    private String documentoCode(DocType docType) {
        if (docType == null) {
            return null;
        }
        return switch (docType) {
            case PASSPORT -> "PAS";
            case NATIONAL_ID -> "NIF";
            case DRIVING_LICENSE -> "PDC";
            case OTHER -> "OTRO";
        };
    }

    private String sexoCode(Gender gender) {
        if (gender == null) {
            return null;
        }
        return switch (gender) {
            case MALE -> "H";
            case FEMALE -> "M";
            case OTHER -> null;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
