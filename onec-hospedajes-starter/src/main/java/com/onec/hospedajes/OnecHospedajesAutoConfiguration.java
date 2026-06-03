package com.onec.hospedajes;

import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.net.ssl.SSLContext;
import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "onec.hospedajes", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(HospedajesProperties.class)
public class OnecHospedajesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SolicitudCodec solicitudCodec() {
        return new SolicitudCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    public HospedajesTransport hospedajesTransport(HospedajesProperties properties) {
        SSLContext sslContext = new SslContextFactory().create(properties);
        return new HospedajesTransport(properties, sslContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public HospedajesClient hospedajesClient(HospedajesTransport transport,
                                             SolicitudCodec codec,
                                             HospedajesProperties properties) {
        return new DefaultHospedajesClient(transport, codec, properties);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public HospedajesCommunicationLog hospedajesCommunicationLog(DataSource dataSource) {
        HospedajesCommunicationLog log = new HospedajesCommunicationLog(Jdbi.create(dataSource));
        log.initSchema();
        return log;
    }

    @Bean
    @ConditionalOnMissingBean
    public HospedajesService hospedajesService(HospedajesClient client,
                                               ObjectProvider<HospedajesCommunicationLog> communicationLog) {
        return new HospedajesService(client, communicationLog.getIfAvailable());
    }
}
