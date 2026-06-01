package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of the inner request XML for an "alta de partes de viajeros". This document is marshalled,
 * ZIP-compressed and Base64-encoded into the {@code <solicitud>} element of the SOAP envelope.
 * Up to 100 comunicaciones may be sent per request.
 */
@XmlRootElement(name = "solicitud")
@XmlAccessorType(XmlAccessType.FIELD)
public class Solicitud {

    @XmlElement(name = "comunicacion")
    private List<Comunicacion> comunicacion = new ArrayList<>();

    public List<Comunicacion> getComunicacion() {
        return comunicacion;
    }

    public void setComunicacion(List<Comunicacion> comunicacion) {
        this.comunicacion = comunicacion;
    }
}
