package com.onec.hospedajes;

import com.onec.hospedajes.model.Solicitud;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serializes a {@link Solicitud} to the wire form the service expects for the {@code <solicitud>}
 * element: UTF-8 XML, ZIP-compressed, then Base64-encoded.
 */
public class SolicitudCodec {

    private final JAXBContext context;

    public SolicitudCodec() {
        try {
            this.context = JAXBContext.newInstance(Solicitud.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to init JAXB context for Solicitud", e);
        }
    }

    /** Marshal to a UTF-8 XML string. */
    public String toXml(Solicitud solicitud) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
            StringWriter writer = new StringWriter();
            marshaller.marshal(solicitud, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to marshal Solicitud", e);
        }
    }

    /** XML string -> ZIP -> Base64, ready for the {@code <solicitud>} SOAP element. */
    public String encode(String xml) {
        return Base64.getEncoder().encodeToString(zip(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Convenience: marshal and encode in one step. */
    public String encode(Solicitud solicitud) {
        return encode(toXml(solicitud));
    }

    private byte[] zip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("solicitud.xml"));
            zip.write(data);
            zip.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ZIP solicitud payload", e);
        }
        return out.toByteArray();
    }
}
