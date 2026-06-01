package com.onec.hospedajes;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "onec.hospedajes")
public class HospedajesProperties {

    /** Pre-production (test) and production endpoints for the Comunicación SOAP service. */
    public static final String PRE_ENDPOINT =
            "https://hospedajes.pre-ses.mir.es/hospedajes-web/ws/v1/comunicacion";
    public static final String PROD_ENDPOINT =
            "https://hospedajes.ses.mir.es/hospedajes-web/ws/v1/comunicacion";

    private boolean enabled = false;

    /** When {@code true}, target the production endpoint; otherwise the pre-production sandbox. */
    private boolean production = false;

    /** Overrides the endpoint URL derived from {@link #production}. */
    private String endpoint;

    /** Arrendador code assigned at registration in the Sede Electrónica (cabecera.codigoArrendador). */
    private String arrendador;

    /** Client application name reported in the header (cabecera.aplicacion). */
    private String aplicacion = "onec";

    /** HTTP Basic credentials issued at registration. */
    private String username;
    private String password;

    /** Max comunicaciones per request; the service caps this at 100. */
    private int maxBatchSize = 100;

    /**
     * Trust store into which the MIR-provided server certificate is imported (spec §2.2 TLS tunnel).
     * When unset, the JVM-default trust store is used.
     */
    private final KeyStoreConfig truststore = new KeyStoreConfig();

    /** Optional client-certificate keystore, only needed if a deployment requires mutual TLS. */
    private final KeyStoreConfig keystore = new KeyStoreConfig();

    public String resolveEndpoint() {
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        return production ? PROD_ENDPOINT : PRE_ENDPOINT;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isProduction() {
        return production;
    }

    public void setProduction(boolean production) {
        this.production = production;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getArrendador() {
        return arrendador;
    }

    public void setArrendador(String arrendador) {
        this.arrendador = arrendador;
    }

    public String getAplicacion() {
        return aplicacion;
    }

    public void setAplicacion(String aplicacion) {
        this.aplicacion = aplicacion;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public KeyStoreConfig getKeystore() {
        return keystore;
    }

    public KeyStoreConfig getTruststore() {
        return truststore;
    }

    public static class KeyStoreConfig {
        /** Classpath or filesystem location (e.g. {@code file:/etc/onec/hospedajes.p12}). */
        private String location;
        private String password;
        private String type = "PKCS12";

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }
}
