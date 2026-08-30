package su.onno.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.AgentRepository;
import su.onno.crm.service.ConversationService;
import su.onno.crm.ui.ConversationView;
import su.onno.crm.ui.CrmLayout;
import su.onno.crm.web.CrmInboxController;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;

class OnnoCrmAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OnnoCrmAutoConfiguration.class))
            .withBean(AgentRepository.class, () -> mock(AgentRepository.class))
            .withBean(ConversationRepository.class, () -> mock(ConversationRepository.class))
            .withBean(ConversationMessageRepository.class, () -> mock(ConversationMessageRepository.class))
            .withBean(CurrentUserResolver.class, () -> mock(CurrentUserResolver.class))
            .withBean(UiAccessService.class, () -> mock(UiAccessService.class));

    @Test
    void installsCrmServicesApiUiAndScannerPackage() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ConversationService.class);
            assertThat(context).hasSingleBean(CrmInboxController.class);
            assertThat(context).hasSingleBean(ConversationView.class);
            assertThat(context).hasSingleBean(CrmLayout.class);
            assertThat(AutoConfigurationPackages.get(context.getBeanFactory()))
                    .contains("su.onno.crm");
        });
    }

    @Test
    void packagesTheCrmWidgetBundle() {
        assertThat(getClass().getClassLoader().getResource("onno-plugins/CrmInbox.js")).isNotNull();
        assertThat(getClass().getClassLoader().getResource(
                "onno-plugins/su-onno-onno-crm-starter-widgets.css")).isNotNull();
    }
}
