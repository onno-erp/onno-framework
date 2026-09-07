package su.onno.crm.service;

import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;

/** Provider boundary. Implementations enqueue durably in the caller's database transaction. */
public interface CrmMessageTransport {
    Connection connection(Conversation conversation);

    /** No network I/O here. A worker delivers after commit. Also used for explicit failed-message retry. */
    void enqueue(Conversation conversation, ConversationMessage message);

    record Connection(boolean connected, String label, int maxTextLength) {}
}
