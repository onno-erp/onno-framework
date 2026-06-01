package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Solicitud;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link HospedajesClient}. Builds the SOAP envelope, encodes the inner request XML
 * (ZIP+Base64) via {@link SolicitudCodec}, posts it through {@link HospedajesTransport}, and parses
 * the response by element local-name so it is resilient to namespace prefixes.
 *
 * <p>Note: the {@code alta} request schema is modelled in full. The inner XML element names for
 * {@code anular}/{@code consultaLote} should be confirmed against the corresponding MIR XSDs before
 * production use; response parsing is already tolerant of either.
 */
public class DefaultHospedajesClient implements HospedajesClient {

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String COM_NS = "http://www.soap.servicios.hospedajes.mir.es/comunicacion";

    private final HospedajesTransport transport;
    private final SolicitudCodec codec;
    private final HospedajesProperties properties;

    public DefaultHospedajesClient(HospedajesTransport transport,
                                   SolicitudCodec codec,
                                   HospedajesProperties properties) {
        this.transport = transport;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    public ComunicacionResult altaPartes(List<Comunicacion> comunicaciones) {
        if (comunicaciones.size() > properties.getMaxBatchSize()) {
            throw new HospedajesException("Batch of " + comunicaciones.size()
                    + " exceeds max " + properties.getMaxBatchSize());
        }
        Solicitud solicitud = new Solicitud();
        solicitud.setComunicacion(new ArrayList<>(comunicaciones));
        String body = codec.encode(solicitud);
        String envelope = envelope("comunicacionRequest", TipoOperacion.ALTA,
                TipoComunicacion.PARTE_VIAJEROS, body);
        return parseComunicacionResponse(transport.post(envelope));
    }

    @Override
    public ComunicacionResult anularComunicaciones(List<String> codigosComunicacion) {
        StringBuilder xml = new StringBuilder("<solicitud>");
        for (String codigo : codigosComunicacion) {
            xml.append("<comunicacion><codigoComunicacion>")
                    .append(escape(codigo))
                    .append("</codigoComunicacion></comunicacion>");
        }
        xml.append("</solicitud>");
        String body = codec.encode(xml.toString());
        String envelope = envelope("comunicacionRequest", TipoOperacion.ANULACION, null, body);
        return parseComunicacionResponse(transport.post(envelope));
    }

    @Override
    public LoteEstado consultaLote(String numeroLote) {
        String xml = "<solicitud><lote>" + escape(numeroLote) + "</lote></solicitud>";
        String body = codec.encode(xml);
        String envelope = envelope("consultaLoteRequest", TipoOperacion.CONSULTA, null, body);
        return parseLoteResponse(transport.post(envelope));
    }

    private String envelope(String operation, TipoOperacion op, TipoComunicacion tipo, String solicitudBase64) {
        StringBuilder sb = new StringBuilder();
        sb.append("<soapenv:Envelope xmlns:soapenv=\"").append(SOAP_NS)
                .append("\" xmlns:com=\"").append(COM_NS).append("\">");
        sb.append("<soapenv:Header/><soapenv:Body>");
        sb.append("<com:").append(operation).append("><peticion><cabecera>");
        sb.append("<codigoArrendador>").append(escape(properties.getArrendador())).append("</codigoArrendador>");
        sb.append("<aplicacion>").append(escape(properties.getAplicacion())).append("</aplicacion>");
        sb.append("<tipoOperacion>").append(op.code()).append("</tipoOperacion>");
        if (tipo != null) {
            sb.append("<tipoComunicacion>").append(tipo.code()).append("</tipoComunicacion>");
        }
        sb.append("</cabecera><solicitud>").append(solicitudBase64).append("</solicitud>");
        sb.append("</peticion></com:").append(operation).append(">");
        sb.append("</soapenv:Body></soapenv:Envelope>");
        return sb.toString();
    }

    private ComunicacionResult parseComunicacionResponse(String responseXml) {
        Document doc = parse(responseXml);
        return new ComunicacionResult(
                intText(doc, "codigo", -1),
                text(doc, "descripcion"),
                text(doc, "numeroLote") != null ? text(doc, "numeroLote") : text(doc, "lote"));
    }

    private LoteEstado parseLoteResponse(String responseXml) {
        Document doc = parse(responseXml);
        List<LoteEstado.ComunicacionEstado> comunicaciones = new ArrayList<>();
        for (Element c : elements(doc, "comunicacion")) {
            comunicaciones.add(new LoteEstado.ComunicacionEstado(
                    childText(c, "referencia"),
                    childText(c, "codigoComunicacion"),
                    childText(c, "error") != null ? childText(c, "error") : childText(c, "descripcion")));
        }
        return new LoteEstado(
                intText(doc, "codigo", -1),
                text(doc, "descripcion"),
                text(doc, "numeroLote") != null ? text(doc, "numeroLote") : text(doc, "lote"),
                comunicaciones);
    }

    private Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new HospedajesException("Failed to parse SES.HOSPEDAJES response", e);
        }
    }

    private String text(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private int intText(Document doc, String localName, int fallback) {
        String value = text(doc, localName);
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<Element> elements(Document doc, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private String childText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
