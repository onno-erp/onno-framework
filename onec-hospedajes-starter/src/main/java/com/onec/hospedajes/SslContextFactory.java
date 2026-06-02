package com.onec.hospedajes;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * Builds an {@link SSLContext} for the SES.HOSPEDAJES TLS tunnel.
 *
 * <p>Per spec §2.2, authentication is HTTP Basic; the TLS requirement is to <em>trust</em> the
 * certificate provided by the MIR by importing it into the client's trust store. The MIR endpoint
 * certificate is issued by FNMT-RCM, whose CA is <em>not</em> in the JVM default {@code cacerts},
 * so a trust store must be configured. A client certificate (keystore / mutual TLS) is optional and
 * only built if explicitly configured.
 *
 * <p>The trust store may be a binary keystore ({@code PKCS12}/{@code JKS}) or — for convenience — a
 * PEM/CRT file (set {@code truststore.type=PEM}, or use a {@code .pem}/{@code .crt}/{@code .cer}
 * location); in the latter case the X.509 certificates are read and trusted directly, no
 * {@code keytool} step required.
 *
 * <p>When no trust store is configured, the bundled FNMT CA ({@link #BUNDLED_FNMT_CA}) is trusted
 * by default so the MIR endpoint works out of the box.
 */
public final class SslContextFactory {

    /** Bundled FNMT CA chain (root + "AC Componentes Informáticos"), trusted by default. */
    public static final String BUNDLED_FNMT_CA = "classpath:ses-hospedajes/fnmt-ca.pem";

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    public SSLContext create(HospedajesProperties properties) {
        HospedajesProperties.KeyStoreConfig keystore = properties.getKeystore();
        HospedajesProperties.KeyStoreConfig truststore = properties.getTruststore();

        boolean hasKeystore = keystore.getLocation() != null && !keystore.getLocation().isBlank();
        boolean hasTruststore = truststore.getLocation() != null && !truststore.getLocation().isBlank();

        // The MIR endpoint cert is issued by FNMT-RCM, whose CA is not in the JVM default cacerts.
        // When no trust store is configured we fall back to the bundled FNMT CA so TLS just works.
        String trustLocation = hasTruststore ? truststore.getLocation() : BUNDLED_FNMT_CA;
        String trustType = hasTruststore ? truststore.getType() : "PEM";

        try {
            KeyManager[] keyManagers = null;
            if (hasKeystore) {
                char[] keyPassword = passwordOf(keystore);
                KeyStore ks = load(keystore.getLocation(), keystore.getType(), keyPassword);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keyPassword);
                keyManagers = kmf.getKeyManagers();
            }

            KeyStore ts = load(trustLocation, trustType, passwordOf(truststore));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            TrustManager[] trustManagers = tmf.getTrustManagers();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build SSLContext for SES.HOSPEDAJES", e);
        }
    }

    private char[] passwordOf(HospedajesProperties.KeyStoreConfig config) {
        return config.getPassword() == null ? new char[0] : config.getPassword().toCharArray();
    }

    private KeyStore load(String location, String type, char[] password) throws Exception {
        if (isPem(location, type)) {
            return loadPem(location);
        }
        Resource resource = resourceLoader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(in, password);
            return ks;
        }
    }

    private boolean isPem(String location, String type) {
        if (type != null && type.equalsIgnoreCase("PEM")) {
            return true;
        }
        String lower = location.toLowerCase();
        return lower.endsWith(".pem") || lower.endsWith(".crt") || lower.endsWith(".cer");
    }

    /** Build an in-memory trust store from one or more PEM/DER X.509 certificates. */
    private KeyStore loadPem(String location) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = resource.getInputStream()) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = factory.generateCertificates(in);
            int i = 0;
            for (Certificate cert : certs) {
                ks.setCertificateEntry("ses-hospedajes-" + i++, cert);
            }
            if (i == 0) {
                throw new IllegalStateException("No X.509 certificates found in " + location);
            }
        }
        return ks;
    }
}
