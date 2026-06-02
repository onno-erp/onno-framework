package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/** {@code direccionType} ({@code tiposGenerales.xsd}) — a person's home address. */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"direccion", "direccionComplementaria", "codigoMunicipio",
        "nombreMunicipio", "codigoPostal", "pais"})
public class Direccion {

    /** Calle, número, escalera, piso, puerta. Required, max 100 chars. */
    @XmlElement(name = "direccion", required = true)
    private String direccion;

    @XmlElement(name = "direccionComplementaria")
    private String direccionComplementaria;

    /** INE 5-digit municipality code. Required when país is Spain. */
    @XmlElement(name = "codigoMunicipio")
    private String codigoMunicipio;

    /** Free-text municipality. Required when país is not Spain. */
    @XmlElement(name = "nombreMunicipio")
    private String nombreMunicipio;

    /** Postal code. Required, max 20 chars. */
    @XmlElement(name = "codigoPostal", required = true)
    private String codigoPostal;

    /** ISO-3166-1 alpha-3 country code (MIR catalog PAIS). Required. */
    @XmlElement(name = "pais", required = true)
    private String pais;

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public String getDireccionComplementaria() {
        return direccionComplementaria;
    }

    public void setDireccionComplementaria(String direccionComplementaria) {
        this.direccionComplementaria = direccionComplementaria;
    }

    public String getCodigoMunicipio() {
        return codigoMunicipio;
    }

    public void setCodigoMunicipio(String codigoMunicipio) {
        this.codigoMunicipio = codigoMunicipio;
    }

    public String getNombreMunicipio() {
        return nombreMunicipio;
    }

    public void setNombreMunicipio(String nombreMunicipio) {
        this.nombreMunicipio = nombreMunicipio;
    }

    public String getCodigoPostal() {
        return codigoPostal;
    }

    public void setCodigoPostal(String codigoPostal) {
        this.codigoPostal = codigoPostal;
    }

    public String getPais() {
        return pais;
    }

    public void setPais(String pais) {
        this.pais = pais;
    }
}
