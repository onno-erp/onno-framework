package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.LocalDate;

/** {@code pagoType} ({@code tiposGenerales.xsd}). Payment data mandated by RD 933/2021. */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"tipoPago", "fechaPago", "medioPago", "titular", "caducidadTarjeta"})
public class Pago {

    /** Payment type code (MIR catalog TIPO_PAGO: e.g. cash, card, transfer). Required. */
    @XmlElement(name = "tipoPago")
    private String tipoPago;

    @XmlElement(name = "fechaPago")
    @XmlJavaTypeAdapter(LocalDateXmlAdapter.class)
    private LocalDate fechaPago;

    /** Card type + number, or IBAN. */
    @XmlElement(name = "medioPago")
    private String medioPago;

    /** Cardholder / account holder name. */
    @XmlElement(name = "titular")
    private String titular;

    /** Card expiry, format MM/AAAA. */
    @XmlElement(name = "caducidadTarjeta")
    private String caducidadTarjeta;

    public String getTipoPago() {
        return tipoPago;
    }

    public void setTipoPago(String tipoPago) {
        this.tipoPago = tipoPago;
    }

    public LocalDate getFechaPago() {
        return fechaPago;
    }

    public void setFechaPago(LocalDate fechaPago) {
        this.fechaPago = fechaPago;
    }

    public String getMedioPago() {
        return medioPago;
    }

    public void setMedioPago(String medioPago) {
        this.medioPago = medioPago;
    }

    public String getTitular() {
        return titular;
    }

    public void setTitular(String titular) {
        this.titular = titular;
    }

    public String getCaducidadTarjeta() {
        return caducidadTarjeta;
    }

    public void setCaducidadTarjeta(String caducidadTarjeta) {
        this.caducidadTarjeta = caducidadTarjeta;
    }
}
