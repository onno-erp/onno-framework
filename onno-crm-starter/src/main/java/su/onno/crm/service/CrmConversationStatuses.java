package su.onno.crm.service;
import java.util.UUID;

import su.onno.types.Ref;
/** Resolves application-authored destinations without relying on labels or UI-edited settings. */
public class CrmConversationStatuses {
    private final CrmStateConfiguration config;
    public CrmConversationStatuses(CrmStateConfiguration config) { this.config=config; }
    public UUID incoming() { return config.transitions() == null ? null : active(transitions().incoming()); }
    public UUID reply() { return config.transitions() == null ? null : active(transitions().reply()); }
    public UUID close() { return active(transitions().close()); }
    public UUID reopen() { return active(transitions().reopen()); }
    public UUID active(UUID id) {
        if(config.conversationStatuses().stream().noneMatch(s->s.choice().id().equals(id)))throw new IllegalArgumentException("Choose a configured conversation status");
        return id;
    }
    private CrmStateConfiguration.Transitions transitions() {
        if(config.transitions()==null)throw new IllegalStateException("The application must supply CrmStateConfiguration conversation transitions");
        return config.transitions();
    }
}
