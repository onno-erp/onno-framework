package su.onno.cluster.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.cluster.ClusterEventBus;
import su.onno.cluster.NoOpClusterEventBus;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnoClusterAutoConfigurationTest {

    @Test
    void usesNoOpBusOnANonPostgresDatasource() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:cluster-cfg-test;DB_CLOSE_DELAY=-1");

        ClusterEventBus bus = new OnnoClusterAutoConfiguration()
                .onnoClusterEventBus(h2, new ObjectMapper(), new OnnoClusterProperties());

        assertThat(bus).isInstanceOf(NoOpClusterEventBus.class);
        assertThat(bus.isDistributed()).isFalse();
    }
}
