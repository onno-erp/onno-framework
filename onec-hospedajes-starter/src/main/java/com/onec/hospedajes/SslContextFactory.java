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

/**
 * Builds an {@link SSLContext} for the SES.HOSPEDAJES TLS tunnel.
 *
 * <p>Per spec §2.2, authentication is HTTP Basic; the TLS requirement is to <em>trust</em> the
 * certificate provided by the MIR by importing it into the client's trust store. A client
 * certificate (keystore / mutual TLS) is therefore optional and only built if explicitly configured.
 *
 * <p>Returns {@code null} when neither a truststore nor a keystore is configured, signalling that
 * the JVM-default SSL configuration should be used.
 */
public final class SslContextFactory {

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    public SSLContext create(HospedajesProperties properties) {
        HospedajesProperties.KeyStoreConfig keystore = properties.getKeystore();
        HospedajesProperties.KeyStoreConfig truststore = properties.getTruststore();

        boolean hasKeystore = keystore.getLocation() != null && !keystore.getLocation().isBlank();
        boolean hasTruststore = truststore.getLocation() != null && !truststore.getLocation().isBlank();
        if (!hasKeystore && !hasTruststore) {
            return null;
        }

        try {
            KeyManager[] keyManagers = null;
            if (hasKeystore) {
                char[] keyPassword = passwordOf(keystore);
                KeyStore ks = load(keystore.getLocation(), keystore.getType(), keyPassword);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keyPassword);
                keyManagers = kmf.getKeyManagers();
            }

            TrustManager[] trustManagers = null;
            if (hasTruststore) {
                KeyStore ts = load(truststore.getLocation(), truststore.getType(), passwordOf(truststore));
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                trustManagers = tmf.getTrustManagers();
            }

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
        Resource resource = resourceLoader.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(in, password);
            return ks;
        }
    }
}
