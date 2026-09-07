package su.onno.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import su.onno.crm.domain.Channel;
import su.onno.crm.service.CrmWorkspaceService;
import su.onno.crm.service.CrmWorkspaceService.Folder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CrmFolderConfigurationTest {
    @Test void foldersAreOrderedAndSurviveOtherCustomizers() {
        var folders = List.of(Folder.channel("telegram", "Telegram", Channel.TELEGRAM),
                new Folder("unread", "Unread", List.of(), List.of(), List.of(), true));
        var service = new CrmWorkspaceService(mock(JdbcTemplate.class), new ObjectMapper(),
                List.of(c -> c.withFolders(folders), c -> c.withActions(c.actions())));
        service.initialize();
        assertThat(service.get().config().folders()).containsExactlyElementsOf(folders);
    }
    @Test void duplicateFolderKeysAreRejected() {
        var folder = Folder.channel("telegram", "Telegram", Channel.TELEGRAM);
        var service = new CrmWorkspaceService(mock(JdbcTemplate.class), new ObjectMapper(),
                List.of(c -> c.withFolders(List.of(folder, folder))));
        assertThatIllegalArgumentException().isThrownBy(service::initialize).withMessageContaining("unique stable keys");
    }
    @Test void folderPayloadIncludesFrameworkEnumIdsAndExplicitMembers() throws Exception {
        var folder = Folder.channel("telegram", "Telegram", Channel.TELEGRAM);
        var payload = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(folder));
        assertThat(payload.get("channelIds").get(0).asText()).isEqualTo(
                su.onno.repository.EnumerationPersistence.resolveId(Channel.class, Channel.TELEGRAM).toString());
        var id = java.util.UUID.randomUUID();
        assertThat(Folder.conversations("team", "Team", List.of(id)).conversationIds()).containsExactly(id);
        assertThatIllegalArgumentException().isThrownBy(() -> Folder.conversations("team", "Team", List.of()));
    }
    @Test void foldersAreOptIn() {
        var service = new CrmWorkspaceService(mock(JdbcTemplate.class), new ObjectMapper(), List.of());
        service.initialize();
        assertThat(service.get().config().folders()).isEmpty();
    }
}
