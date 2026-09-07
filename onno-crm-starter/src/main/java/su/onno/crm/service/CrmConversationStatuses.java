package su.onno.crm.service;
import java.util.UUID;
import su.onno.crm.domain.ChatStatus;
import su.onno.types.Ref;
/** Resolves application-authored destinations without relying on labels or UI-edited settings. */
public class CrmConversationStatuses {
    private final CrmStateConfiguration config;
    public CrmConversationStatuses(CrmStateConfiguration config) { this.config=config; }
    public Ref<ChatStatus> incoming() { return active(transitions().incoming()); }
    public Ref<ChatStatus> reply() { return active(transitions().reply()); }
    public Ref<ChatStatus> close() { return active(transitions().close()); }
    public Ref<ChatStatus> reopen() { return active(transitions().reopen()); }
    public Ref<ChatStatus> active(UUID id) {
        if(config.conversationStatuses().stream().noneMatch(s->s.choice().id().equals(id)))throw new IllegalArgumentException("Choose a configured conversation status");
        return Ref.of(ChatStatus.class,id);
    }
    private CrmStateConfiguration.Transitions transitions() {
        if(config.transitions()==null)throw new IllegalStateException("The application must supply CrmStateConfiguration conversation transitions");
        return config.transitions();
    }
}
