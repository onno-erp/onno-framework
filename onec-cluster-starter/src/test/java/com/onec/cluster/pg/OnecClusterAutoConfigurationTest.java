package com.onec.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.cluster.ClusterEventBus;
import com.onec.cluster.NoOpClusterEventBus;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnecClusterAutoConfigurationTest {

    @Test
    void usesNoOpBusOnANonPostgresDatasource() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:cluster-cfg-test;DB_CLOSE_DELAY=-1");

        ClusterEventBus bus = new OnecClusterAutoConfiguration()
                .onecClusterEventBus(h2, new ObjectMapper(), new OnecClusterProperties());

        assertThat(bus).isInstanceOf(NoOpClusterEventBus.class);
        assertThat(bus.isDistributed()).isFalse();
    }
}
