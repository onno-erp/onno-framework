package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** {@code contratoHospedajeType} ({@code tiposGenerales.xsd}) — booking/contract data for a parte. */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"referencia", "fechaContrato", "fechaEntrada", "fechaSalida",
        "numPersonas", "numHabitaciones", "internet", "pago"})
public class Contrato {

    /** Caller's own contract reference. Required. */
    @XmlElement(name = "referencia")
    private String referencia;

    @XmlElement(name = "fechaContrato")
    @XmlJavaTypeAdapter(LocalDateXmlAdapter.class)
    private LocalDate fechaContrato;

    /** Date and time of entry to the accommodation. Required. */
    @XmlElement(name = "fechaEntrada")
    @XmlJavaTypeAdapter(LocalDateTimeXmlAdapter.class)
    private LocalDateTime fechaEntrada;

    /** Date and time of departure from the accommodation. Required ({@code xsd:dateTime}). */
    @XmlElement(name = "fechaSalida")
    @XmlJavaTypeAdapter(LocalDateTimeXmlAdapter.class)
    private LocalDateTime fechaSalida;

    @XmlElement(name = "numPersonas")
    private Integer numPersonas;

    @XmlElement(name = "numHabitaciones")
    private Integer numHabitaciones;

    @XmlElement(name = "internet")
    private Boolean internet;

    @XmlElement(name = "pago")
    private Pago pago;

    public String getReferencia() {
        return referencia;
    }

    public void setReferencia(String referencia) {
        this.referencia = referencia;
    }

    public LocalDate getFechaContrato() {
        return fechaContrato;
    }

    public void setFechaContrato(LocalDate fechaContrato) {
        this.fechaContrato = fechaContrato;
    }

    public LocalDateTime getFechaEntrada() {
        return fechaEntrada;
    }

    public void setFechaEntrada(LocalDateTime fechaEntrada) {
        this.fechaEntrada = fechaEntrada;
    }

    public LocalDateTime getFechaSalida() {
        return fechaSalida;
    }

    public void setFechaSalida(LocalDateTime fechaSalida) {
        this.fechaSalida = fechaSalida;
    }

    public Integer getNumPersonas() {
        return numPersonas;
    }

    public void setNumPersonas(Integer numPersonas) {
        this.numPersonas = numPersonas;
    }

    public Integer getNumHabitaciones() {
        return numHabitaciones;
    }

    public void setNumHabitaciones(Integer numHabitaciones) {
        this.numHabitaciones = numHabitaciones;
    }

    public Boolean getInternet() {
        return internet;
    }

    public void setInternet(Boolean internet) {
        this.internet = internet;
    }

    public Pago getPago() {
        return pago;
    }

    public void setPago(Pago pago) {
        this.pago = pago;
    }
}
