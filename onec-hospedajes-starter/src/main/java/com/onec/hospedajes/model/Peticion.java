package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Root of the inner request XML for an "alta de parte de hospedaje" ({@code altaParteHospedaje.xsd}
 * &rarr; element {@code peticion}). This document is marshalled, ZIP-compressed and Base64-encoded
 * into the {@code <solicitud>} element of the SOAP {@code comunicacionRequest} envelope.
 */
@XmlRootElement(name = "peticion")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"solicitud"})
public class Peticion {

    @XmlElement(name = "solicitud", required = true)
    private Solicitud solicitud;

    public Peticion() {
    }

    public Peticion(Solicitud solicitud) {
        this.solicitud = solicitud;
    }

    public Solicitud getSolicitud() {
        return solicitud;
    }

    public void setSolicitud(Solicitud solicitud) {
        this.solicitud = solicitud;
    }
}
