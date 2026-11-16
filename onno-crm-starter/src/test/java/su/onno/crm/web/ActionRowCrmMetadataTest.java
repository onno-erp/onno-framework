package su.onno.crm.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.access.AccessSubject;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.ui.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionRowCrmMetadataTest {

    @Test
    void conversationMenuAndExecutionResolveCamelCaseFields() {
        AtomicInteger handled = new AtomicInteger();
        EntityView<Conversation> view = new EntityView<>() {
            @Override
            public Class<Conversation> entity() {
                return Conversation.class;
            }
            @Override
            public void actions(ActionSpec<Conversation> spec) {
                spec.action("review").scope(ActionScope.ROW)
                        .label(row -> row.text(Conversation::getLastMessagePreview))
                        .visibleWhen(row -> row.get(Conversation::getLastMessagePreview) != null)
                        .enabledWhen(row -> "Review me".equals(row.text(Conversation::getLastMessagePreview)))
                        .handler(ctx -> {
                            handled.incrementAndGet();
                            return ActionResult.ok();
                        });
            }
        };
        var scopes = mock(CrmInboxWorkspaceService.class);
        var catalogs = mock(CatalogQueryService.class);
        var descriptor = new MetadataScanner(new DefaultNamingStrategy()).scan(Conversation.class);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Principal principal = () -> "reader";
        when(catalogs.forClass(Conversation.class)).thenReturn(descriptor);
        when(catalogs.get(AccessSubject.system(), descriptor, id))
                .thenReturn(Map.of("last_message_preview", "Review me"));
        when(scopes.permitted(isNull(), eq(principal), eq(true))).thenReturn(true);
        var controller = new CrmActionController(scopes, new UiActionResolver(List.of(view)),
                mock(UiAccessService.class), catalogs, new UiProperties());
        assertThat((List<?>) controller.list("support", id, principal))
                .singleElement().isEqualTo(Map.of("id", "review", "label", "Review me", "visible", true, "enabled", true));
        controller.run("support", id, "review", Map.of(), principal, AccessSubject.user("reader", Set.of()));
        assertThat(handled).hasValue(1);
        when(catalogs.get(AccessSubject.system(), descriptor, id))
                .thenReturn(Map.of("last_message_preview", "Already reviewed"));
        assertThatThrownBy(() -> controller.run("support", id, "review", Map.of(), principal,
                AccessSubject.user("reader", Set.of()))).isInstanceOf(ResponseStatusException.class);
        assertThat(handled).hasValue(1);
    }
}
