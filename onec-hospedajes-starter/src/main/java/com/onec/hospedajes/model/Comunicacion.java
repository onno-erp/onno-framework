package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

import java.util.ArrayList;
import java.util.List;

/** One {@code comunicacion} — a parte de viajeros for a single contract at one establishment. */
@XmlAccessorType(XmlAccessType.FIELD)
public class Comunicacion {

    /** Establishment/property code assigned by the Hospedajes registry. Required. */
    @XmlElement(name = "codigoEstablecimiento")
    private String codigoEstablecimiento;

    @XmlElement(name = "contrato")
    private Contrato contrato;

    @XmlElement(name = "persona")
    private List<Persona> persona = new ArrayList<>();

    public String getCodigoEstablecimiento() {
        return codigoEstablecimiento;
    }

    public void setCodigoEstablecimiento(String codigoEstablecimiento) {
        this.codigoEstablecimiento = codigoEstablecimiento;
    }

    public Contrato getContrato() {
        return contrato;
    }

    public void setContrato(Contrato contrato) {
        this.contrato = contrato;
    }

    public List<Persona> getPersona() {
        return persona;
    }

    public void setPersona(List<Persona> persona) {
        this.persona = persona;
    }
}
