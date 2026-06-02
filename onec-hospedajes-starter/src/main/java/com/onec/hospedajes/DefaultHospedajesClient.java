package com.onec.hospedajes;

import com.onec.hospedajes.model.Comunicacion;
import com.onec.hospedajes.model.Peticion;
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
 * Default {@link HospedajesClient}, wired to the SES.HOSPEDAJES {@code ComunicacionPort} (WSDL
 * v3.1.3). Builds each SOAP operation exactly as the WSDL defines it, posts it through
 * {@link HospedajesTransport}, and parses the response by element local-name so it is resilient to
 * namespace prefixes.
 *
 * <ul>
 *   <li>{@code alta}/{@code anular} use the {@code comunicacion} operation: a {@code peticion} with
 *       a {@code cabecera} and a ZIP+Base64 {@code solicitud}.</li>
 *   <li>{@code consultaLote} uses the {@code consultaLote} operation: a plain {@code codigosLote}
 *       list with no cabecera and no compression.</li>
 * </ul>
 */
public class DefaultHospedajesClient implements HospedajesClient {

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String COM_NS = "http://www.soap.servicios.hospedajes.mir.es/comunicacion";
    private static final String ANULAR_NS = "http://www.neg.hospedajes.mir.es/anularComunicacion";

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
    public ComunicacionResult altaPartes(String codigoEstablecimiento, List<Comunicacion> comunicaciones) {
        if (comunicaciones.size() > properties.getMaxBatchSize()) {
            throw new HospedajesException("Batch of " + comunicaciones.size()
                    + " exceeds max " + properties.getMaxBatchSize());
        }
        Solicitud solicitud = new Solicitud();
        solicitud.setCodigoEstablecimiento(codigoEstablecimiento);
        solicitud.setComunicacion(new ArrayList<>(comunicaciones));
        String body = codec.encode(new Peticion(solicitud));

        String envelope = soap("<com:comunicacionRequest><peticion>"
                + cabecera(TipoOperacion.ALTA, TipoComunicacion.PARTE_VIAJEROS)
                + "<solicitud>" + body + "</solicitud>"
                + "</peticion></com:comunicacionRequest>");
        return parseComunicacionResponse(transport.post(envelope));
    }

    @Override
    public ComunicacionResult anularComunicaciones(List<String> codigosComunicacion) {
        StringBuilder inner = new StringBuilder("<comunicaciones xmlns=\"").append(ANULAR_NS).append("\">");
        for (String codigo : codigosComunicacion) {
            inner.append("<codigoComunicacion>").append(escape(codigo)).append("</codigoComunicacion>");
        }
        inner.append("</comunicaciones>");
        String body = codec.encode(inner.toString());

        String envelope = soap("<com:comunicacionRequest><peticion>"
                + cabecera(TipoOperacion.ANULACION, null)
                + "<solicitud>" + body + "</solicitud>"
                + "</peticion></com:comunicacionRequest>");
        return parseComunicacionResponse(transport.post(envelope));
    }

    @Override
    public LoteEstado consultaLote(String numeroLote) {
        String envelope = soap("<com:consultaLoteRequest><codigosLote><lote>"
                + escape(numeroLote) + "</lote></codigosLote></com:consultaLoteRequest>");
        return parseLoteResponse(transport.post(envelope), numeroLote);
    }

    // --- envelope construction -------------------------------------------------------------------

    private String soap(String bodyXml) {
        return "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\" xmlns:com=\"" + COM_NS + "\">"
                + "<soapenv:Header/><soapenv:Body>" + bodyXml + "</soapenv:Body></soapenv:Envelope>";
    }

    private String cabecera(TipoOperacion op, TipoComunicacion tipo) {
        StringBuilder sb = new StringBuilder("<cabecera>");
        sb.append("<codigoArrendador>").append(escape(properties.getArrendador())).append("</codigoArrendador>");
        sb.append("<aplicacion>").append(escape(properties.getAplicacion())).append("</aplicacion>");
        sb.append("<tipoOperacion>").append(op.code()).append("</tipoOperacion>");
        if (tipo != null) {
            sb.append("<tipoComunicacion>").append(tipo.code()).append("</tipoComunicacion>");
        }
        return sb.append("</cabecera>").toString();
    }

    // --- response parsing ------------------------------------------------------------------------

    private ComunicacionResult parseComunicacionResponse(String responseXml) {
        Document doc = parse(responseXml);
        Element response = descendant(doc.getDocumentElement(), "comunicacionResponse");
        if (response == null) {
            throw new HospedajesException("Unexpected SES.HOSPEDAJES response: " + responseXml);
        }
        Element respuesta = child(response, "respuesta");
        return new ComunicacionResult(
                intText(respuesta, "codigo", -1),
                text(respuesta, "descripcion"),
                text(respuesta, "lote"));
    }

    private LoteEstado parseLoteResponse(String responseXml, String numeroLote) {
        Document doc = parse(responseXml);
        Element response = descendant(doc.getDocumentElement(), "consultaLoteResponse");
        if (response == null) {
            throw new HospedajesException("Unexpected SES.HOSPEDAJES response: " + responseXml);
        }
        Element respuesta = child(response, "respuesta");
        // We query a single lote, so there is at most one <resultado> (wscomunicacionType).
        Element resultado = child(response, "resultado");

        int codigoEstado = -1;
        String descEstado = null;
        String lote = numeroLote;
        List<LoteEstado.ComunicacionEstado> comunicaciones = new ArrayList<>();
        if (resultado != null) {
            codigoEstado = intText(resultado, "codigoEstado", -1);
            descEstado = text(resultado, "descEstado");
            String loteText = text(resultado, "lote");
            if (loteText != null) {
                lote = loteText;
            }
            Element resultados = child(resultado, "resultadoComunicaciones");
            if (resultados != null) {
                for (Element rc : children(resultados, "resultadoComunicacion")) {
                    comunicaciones.add(new LoteEstado.ComunicacionEstado(
                            intText(rc, "orden", -1),
                            Boolean.parseBoolean(text(rc, "anulada")),
                            text(rc, "codigoComunicacion"),
                            text(rc, "tipoError"),
                            text(rc, "error")));
                }
            }
        }
        return new LoteEstado(
                intText(respuesta, "codigo", -1),
                text(respuesta, "descripcion"),
                lote, codigoEstado, descEstado, comunicaciones);
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

    /** First element with the given local name anywhere at or below {@code root}. */
    private static Element descendant(Element root, String localName) {
        if (root == null) {
            return null;
        }
        if (localName.equals(root.getLocalName())) {
            return root;
        }
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    /** First direct child element with the given local name. */
    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    /** All direct child elements with the given local name. */
    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && localName.equals(e.getLocalName())) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    private static String text(Element parent, String localName) {
        Element e = child(parent, localName);
        return e == null ? null : e.getTextContent();
    }

    private static int intText(Element parent, String localName, int fallback) {
        String value = text(parent, localName);
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
