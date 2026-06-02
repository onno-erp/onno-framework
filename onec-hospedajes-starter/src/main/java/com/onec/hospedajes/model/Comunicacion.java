package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code comunicacionType} of {@code altaParteHospedaje.xsd}: a parte de viajeros for a single
 * contract — the contract block plus its travelers. The establishment is carried once on the
 * enclosing {@link Solicitud}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"contrato", "persona"})
public class Comunicacion {

    @XmlElement(name = "contrato", required = true)
    private Contrato contrato;

    @XmlElement(name = "persona", required = true)
    private List<Persona> persona = new ArrayList<>();

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
