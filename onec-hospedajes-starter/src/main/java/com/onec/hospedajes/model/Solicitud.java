package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code solicitudType} of {@code altaParteHospedaje.xsd}: the establishment whose travelers are
 * being registered, followed by one {@code comunicacion} per contract. Up to 100 comunicaciones
 * may be sent per request (the service caps the batch at 100).
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"codigoEstablecimiento", "comunicacion"})
public class Solicitud {

    /** Establishment code assigned by the Hospedajes registry. Required, max 10 chars. */
    @XmlElement(name = "codigoEstablecimiento", required = true)
    private String codigoEstablecimiento;

    @XmlElement(name = "comunicacion", required = true)
    private List<Comunicacion> comunicacion = new ArrayList<>();

    public String getCodigoEstablecimiento() {
        return codigoEstablecimiento;
    }

    public void setCodigoEstablecimiento(String codigoEstablecimiento) {
        this.codigoEstablecimiento = codigoEstablecimiento;
    }

    public List<Comunicacion> getComunicacion() {
        return comunicacion;
    }

    public void setComunicacion(List<Comunicacion> comunicacion) {
        this.comunicacion = comunicacion;
    }
}
