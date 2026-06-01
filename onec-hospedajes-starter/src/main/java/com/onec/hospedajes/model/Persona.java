package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.LocalDate;

/** Bloque {@code persona} — one traveler on the contract. Repeats 1..n per comunicación. */
@XmlAccessorType(XmlAccessType.FIELD)
public class Persona {

    /** Role in the contract; for partes de viajeros always {@code "VI"} (viajero). */
    @XmlElement(name = "rol")
    private String rol = "VI";

    @XmlElement(name = "nombre")
    private String nombre;

    @XmlElement(name = "apellido1")
    private String apellido1;

    /** Required when the document is a Spanish ID. */
    @XmlElement(name = "apellido2")
    private String apellido2;

    /** MIR catalog TIPO_DOCUMENTO code. Required for travelers of legal age. */
    @XmlElement(name = "tipoDocumento")
    private String tipoDocumento;

    /** Must match the format implied by {@link #tipoDocumento}. */
    @XmlElement(name = "numeroDocumento")
    private String numeroDocumento;

    /** Document support number (número de soporte) for DNI/NIE. */
    @XmlElement(name = "soporteDocumento")
    private String soporteDocumento;

    @XmlElement(name = "fechaNacimiento")
    @XmlJavaTypeAdapter(LocalDateXmlAdapter.class)
    private LocalDate fechaNacimiento;

    /** ISO-3166 alpha-3 nationality code (MIR catalog PAIS). */
    @XmlElement(name = "nacionalidad")
    private String nacionalidad;

    /** MIR catalog SEXO code. */
    @XmlElement(name = "sexo")
    private String sexo;

    @XmlElement(name = "direccion")
    private Direccion direccion;

    /** At least one of telefono / telefono2 / correo is required. */
    @XmlElement(name = "telefono")
    private String telefono;

    @XmlElement(name = "telefono2")
    private String telefono2;

    @XmlElement(name = "correo")
    private String correo;

    /** Relationship to the contract holder; required when the traveler is a minor. */
    @XmlElement(name = "parentesco")
    private String parentesco;

    public String getRol() {
        return rol;
    }

    public void setRol(String rol) {
        this.rol = rol;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getApellido1() {
        return apellido1;
    }

    public void setApellido1(String apellido1) {
        this.apellido1 = apellido1;
    }

    public String getApellido2() {
        return apellido2;
    }

    public void setApellido2(String apellido2) {
        this.apellido2 = apellido2;
    }

    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public void setTipoDocumento(String tipoDocumento) {
        this.tipoDocumento = tipoDocumento;
    }

    public String getNumeroDocumento() {
        return numeroDocumento;
    }

    public void setNumeroDocumento(String numeroDocumento) {
        this.numeroDocumento = numeroDocumento;
    }

    public String getSoporteDocumento() {
        return soporteDocumento;
    }

    public void setSoporteDocumento(String soporteDocumento) {
        this.soporteDocumento = soporteDocumento;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public void setFechaNacimiento(LocalDate fechaNacimiento) {
        this.fechaNacimiento = fechaNacimiento;
    }

    public String getNacionalidad() {
        return nacionalidad;
    }

    public void setNacionalidad(String nacionalidad) {
        this.nacionalidad = nacionalidad;
    }

    public String getSexo() {
        return sexo;
    }

    public void setSexo(String sexo) {
        this.sexo = sexo;
    }

    public Direccion getDireccion() {
        return direccion;
    }

    public void setDireccion(Direccion direccion) {
        this.direccion = direccion;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getTelefono2() {
        return telefono2;
    }

    public void setTelefono2(String telefono2) {
        this.telefono2 = telefono2;
    }

    public String getCorreo() {
        return correo;
    }

    public void setCorreo(String correo) {
        this.correo = correo;
    }

    public String getParentesco() {
        return parentesco;
    }

    public void setParentesco(String parentesco) {
        this.parentesco = parentesco;
    }
}
