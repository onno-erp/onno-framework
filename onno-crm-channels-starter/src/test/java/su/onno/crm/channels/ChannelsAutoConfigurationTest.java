package su.onno.crm.channels;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import su.onno.crm.service.CrmCustomerBinding;
import su.onno.crm.service.CrmMessageTransport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChannelsAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OnnoCrmChannelsAutoConfiguration.class));

    @Test void noHostBindingInstallsNoChannelConfiguration() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CrmChannelsProperties.class);
            assertThat(context).doesNotHaveBean(CrmMessageTransport.class);
        });
    }

    @Test void bindingCustomersDoesNotEnableAnyProvider() {
        runner.withBean(CrmCustomerBinding.class, () -> mock(CrmCustomerBinding.class)).run(context -> {
            assertThat(context).hasSingleBean(CrmChannelsProperties.class);
            assertThat(context).doesNotHaveBean(CrmMessageTransport.class);
        });
    }
}
