package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/** Bloque {@code direccion} (spec section 4.1) — a person's home address. */
@XmlAccessorType(XmlAccessType.FIELD)
public class Direccion {

    /** Calle, número, escalera, piso, puerta. Required. */
    @XmlElement(name = "direccion")
    private String direccion;

    @XmlElement(name = "direccionComplem")
    private String direccionComplem;

    /** INE 5-digit municipality code. Required when país is Spain. */
    @XmlElement(name = "codigoMunicipio")
    private String codigoMunicipio;

    /** Free-text municipality. Required when país is not Spain. */
    @XmlElement(name = "nombreMunicipio")
    private String nombreMunicipio;

    @XmlElement(name = "codigoPostal")
    private String codigoPostal;

    /** ISO-3166 alpha-3 country code (MIR catalog PAIS). Required. */
    @XmlElement(name = "pais")
    private String pais;

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public String getDireccionComplem() {
        return direccionComplem;
    }

    public void setDireccionComplem(String direccionComplem) {
        this.direccionComplem = direccionComplem;
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
