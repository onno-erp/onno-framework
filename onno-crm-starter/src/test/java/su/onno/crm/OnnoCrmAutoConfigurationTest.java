package su.onno.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.ConversationService;
import su.onno.crm.web.CrmInboxController;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;

class OnnoCrmAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OnnoCrmAutoConfiguration.class))
            .withBean(su.onno.crm.repository.ContactIdentityRepository.class, () -> mock(su.onno.crm.repository.ContactIdentityRepository.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withBean(su.onno.crm.repository.InboxRepository.class, () -> mock(su.onno.crm.repository.InboxRepository.class))
            .withBean(ConversationRepository.class, () -> mock(ConversationRepository.class))
            .withBean(ConversationMessageRepository.class, () -> mock(ConversationMessageRepository.class))
            .withBean(su.onno.ui.CatalogQueryService.class, () -> mock(su.onno.ui.CatalogQueryService.class))
            .withBean(su.onno.ui.UiActionResolver.class, () -> mock(su.onno.ui.UiActionResolver.class))
            .withBean(su.onno.ui.UiViewResolver.class, () -> mock(su.onno.ui.UiViewResolver.class))
            .withBean(su.onno.ui.UiEventPublisher.class, () -> mock(su.onno.ui.UiEventPublisher.class))
            .withBean(su.onno.ui.comments.CommentService.class, () -> mock(su.onno.ui.comments.CommentService.class))
            .withBean(su.onno.ui.comments.CommentAuthorAvatars.class, () -> mock(su.onno.ui.comments.CommentAuthorAvatars.class))
            .withBean(CurrentUserResolver.class, () -> mock(CurrentUserResolver.class))
            .withBean(UiAccessService.class, () -> mock(UiAccessService.class));

    @Test
    void installsCrmServicesApiUiAndScannerPackage() {
        contextRunner.withBean(su.onno.crm.service.CrmCustomerBinding.class,TestBindings::customers).run(context -> {
            assertThat(context).hasSingleBean(ConversationService.class);
            assertThat(context).hasSingleBean(CrmInboxController.class);
            assertThat(context.getBeansOfType(su.onno.ui.Layout.class)).isEmpty();
            assertThat(context.getBeansOfType(su.onno.ui.Page.class)).isEmpty();
            assertThat(context.getBeansOfType(su.onno.ui.EntityView.class)).isEmpty();
            assertThat(context).doesNotHaveBean(su.onno.crm.service.CrmAgentBinding.class);
            assertThat(AutoConfigurationPackages.get(context.getBeanFactory()))
                    .contains("su.onno.crm");
        });
    }

    @Test
    void noBindingInstallsNoCrmServicesOrMetadataPackage() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ConversationService.class);
            assertThat(context).doesNotHaveBean(CrmInboxController.class);
            assertThat(AutoConfigurationPackages.has(context.getBeanFactory())).isFalse();
        });
    }

    @Test
    void packagesTheCrmWidgetBundle() {
        assertThat(getClass().getClassLoader().getResource("onno-plugins/CrmInbox.js")).isNotNull();
        assertThat(getClass().getClassLoader().getResource(
                "onno-plugins/su-onno-onno-crm-starter-widgets.css")).isNotNull();
    }
}
