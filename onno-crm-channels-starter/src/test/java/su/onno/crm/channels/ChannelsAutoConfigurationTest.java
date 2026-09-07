package su.onno.crm.channels;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import su.onno.crm.service.CrmMessageTransport;
class ChannelsAutoConfigurationTest {
 @Test void allProvidersAreDisabledByDefault(){new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OnnoCrmChannelsAutoConfiguration.class)).run(context->{assertThat(context).hasSingleBean(CrmChannelsProperties.class);assertThat(context).doesNotHaveBean(CrmMessageTransport.class);});}
}
