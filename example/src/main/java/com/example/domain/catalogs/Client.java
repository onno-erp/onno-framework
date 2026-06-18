package com.example.domain.catalogs;

import com.example.domain.enumerations.DocType;
import com.example.domain.enumerations.Gender;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A guest / billing customer — a {@code @Catalog} of people the business deals with. Referenced as a
 * {@code Ref<Client>} from {@link com.example.domain.documents.Booking} (the primary client and each
 * {@link com.example.domain.documents.Guest} row), {@link com.example.domain.documents.Bill}, and
 * {@link com.example.domain.documents.Payment}; it is also the dimension of the Receivables register.
 * The {@code docType} / {@code nationality} / {@code municipalityCode} fields capture what Spain's
 * SES.HOSPEDAJES lodging registry requires for each traveler.
 */
@Catalog(name = "Clients", codeLength = 12, codePrefix = "C-", context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"}, writeRoles = {"RENTALS"})
@Getter
@Setter
public class Client extends CatalogObject {

    @Attribute(length = 100)
    private String firstName;

    @Attribute(length = 100)
    private String lastName1;

    @Attribute(length = 100)
    private String lastName2;

    @Attribute(displayName = "Gender")
    private Gender gender;

    @Attribute
    private LocalDate birthday;

    @Attribute(displayName = "Document type")
    private DocType docType;

    @Attribute(displayName = "ID / Passport No.", length = 50)
    private String docNumber;

    /** Número de soporte del documento — required by SES.HOSPEDAJES when the doc type is NIF or NIE. */
    @Attribute(displayName = "Document support No.", length = 9)
    private String docSupportNumber;

    @Attribute(displayName = "Document issued on")
    private LocalDate docIssuedOn;

    @Attribute
    private Ref<Country> nationality;

    @Attribute(length = 255)
    private String address;

    @Attribute(length = 100)
    private String city;

    /** INE 5-digit municipality code — required by SES.HOSPEDAJES when the country is Spain. */
    @Attribute(displayName = "Municipality code (INE)", length = 5)
    private String municipalityCode;

    @Attribute(length = 20)
    private String postCode;

    @Attribute
    private Ref<Country> country;

    @Attribute(length = 200)
    private String email;

    @Attribute(length = 50)
    private String mobile;
}
