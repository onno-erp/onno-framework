package com.example.integration.hospedajes;

import com.onec.hospedajes.HospedajesCommunicationLog;
import com.onec.hospedajes.HospedajesService;
import com.onec.types.RefResolver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the rentals domain to the SES.HOSPEDAJES starter: a parte de viajeros is submitted when a
 * booking is checked in, and a scheduled job reconciles outstanding batches. Active only when
 * {@code onec.hospedajes.enabled=true} (same switch that activates the starter's beans).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "onec.hospedajes", name = "enabled", havingValue = "true")
public class HospedajesIntegrationConfig {

    @Bean
    public BookingParteMapper bookingParteMapper(RefResolver refResolver) {
        return new BookingParteMapper(refResolver);
    }

    @Bean
    public BookingHospedajesCallback bookingHospedajesCallback(HospedajesService hospedajes,
                                                               HospedajesCommunicationLog ledger,
                                                               BookingParteMapper mapper) {
        return new BookingHospedajesCallback(hospedajes, ledger, mapper);
    }

    @Bean
    public HospedajesReconcileJob hospedajesReconcileJob(HospedajesService hospedajes) {
        return new HospedajesReconcileJob(hospedajes);
    }
}
