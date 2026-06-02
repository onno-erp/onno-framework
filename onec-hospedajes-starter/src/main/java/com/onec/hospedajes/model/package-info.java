/**
 * JAXB model for the inner {@code <solicitud>} payload of an "alta de parte de hospedaje"
 * (parte de viajeros, tipoComunicacion=PV), as defined by {@code altaParteHospedaje.xsd} and the
 * shared {@code tiposGenerales.xsd} (MIR SES.HOSPEDAJES, RD 933/2021, spec v3.1.3).
 *
 * <p>The {@code altaParteHospedaje} schema uses {@code elementFormDefault="unqualified"}: only the
 * root {@code peticion} element is namespace-qualified; every nested element is unqualified. Setting
 * the package namespace together with {@link jakarta.xml.bind.annotation.XmlNsForm#UNQUALIFIED}
 * reproduces that exactly.
 */
@XmlSchema(
        namespace = "http://www.neg.hospedajes.mir.es/altaParteHospedaje",
        elementFormDefault = XmlNsForm.UNQUALIFIED)
package com.onec.hospedajes.model;

import jakarta.xml.bind.annotation.XmlNsForm;
import jakarta.xml.bind.annotation.XmlSchema;
