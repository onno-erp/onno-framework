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
import su.onno.service.CurrentUserResolver;
import su.onno.service.AccessService;

class OnnoCrmAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OnnoCrmAutoConfiguration.class))
            .withBean(su.onno.crm.repository.ContactIdentityRepository.class, () -> mock(su.onno.crm.repository.ContactIdentityRepository.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
            .withBean(su.onno.crm.repository.InboxRepository.class, () -> mock(su.onno.crm.repository.InboxRepository.class))
            .withBean(su.onno.crm.web.CrmFeedQuery.class, () -> mock(su.onno.crm.web.CrmFeedQuery.class))
            .withBean(ConversationRepository.class, () -> mock(ConversationRepository.class))
            .withBean(ConversationMessageRepository.class, () -> mock(ConversationMessageRepository.class))
            .withBean(su.onno.service.CatalogQueryService.class, () -> mock(su.onno.service.CatalogQueryService.class))
            .withBean(su.onno.command.CommandRegistry.class, () -> mock(su.onno.command.CommandRegistry.class))
            .withBean(su.onno.web.DefaultListViews.class, () -> mock(su.onno.web.DefaultListViews.class))
            .withBean(su.onno.web.EventPublisher.class, () -> mock(su.onno.web.EventPublisher.class))
            .withBean(su.onno.service.WebProperties.class, su.onno.service.WebProperties::new)
            .withBean(su.onno.web.comments.CommentService.class, () -> mock(su.onno.web.comments.CommentService.class))
            .withBean(su.onno.web.comments.CommentAuthorAvatars.class, () -> mock(su.onno.web.comments.CommentAuthorAvatars.class))
            .withBean(su.onno.service.AccessSubjectResolver.class, () -> mock(su.onno.service.AccessSubjectResolver.class))
            .withBean(CurrentUserResolver.class, () -> mock(CurrentUserResolver.class))
            .withBean(AccessService.class, () -> su.onno.crm.TestBindings.withGrants(mock(AccessService.class)));

    @Test
    void installsCrmServicesApiUiAndScannerPackage() {
        contextRunner.withBean(su.onno.crm.service.CrmCustomerBinding.class,TestBindings::customers).run(context -> {
            assertThat(context).hasSingleBean(ConversationService.class);
            assertThat(context).hasSingleBean(CrmInboxController.class);
            assertThat(context.getBeansOfType(su.onno.command.Commands.class)).isEmpty();
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
}
