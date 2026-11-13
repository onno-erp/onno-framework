package su.onno.crm.service;

import java.security.Principal;
import java.util.List;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;

/** Routes a conversation to exactly one connected provider, retaining its transaction boundary. */
public final class CrmMessageRouter implements CrmMessageTransport {
    private final List<CrmMessageTransport> transports;

    public CrmMessageRouter(List<CrmMessageTransport> transports) {
        this.transports = List.copyOf(transports);
    }

    private record Route(CrmMessageTransport transport, Connection connection) {}

    private Route resolve(Conversation conversation, Principal principal) {
        Route selected = null;
        Connection unavailable = new Connection(false, "Messaging channel is not connected", 8000);
        for (CrmMessageTransport transport : transports) {
            if (!transport.supports(conversation)) continue;
            Connection connection = principal == null
                    ? transport.connection(conversation) : transport.connection(conversation, principal);
            if (!connection.connected()) {
                unavailable = connection;
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException("Multiple messaging providers claim this conversation");
            }
            selected = new Route(transport, connection);
        }
        return selected == null ? new Route(null, unavailable) : selected;
    }

    @Override public Connection connection(Conversation conversation) {
        return resolve(conversation, null).connection();
    }

    @Override public Connection connection(Conversation conversation, Principal principal) {
        return resolve(conversation, principal).connection();
    }

    @Override public void enqueue(Conversation conversation, ConversationMessage message) {
        Route route = resolve(conversation, null);
        if (route.transport() == null || !route.connection().canSend()) throw new IllegalArgumentException(route.connection().unavailableReason());
        route.transport().enqueue(conversation, message);
    }

    /** Every provider that handles the conversation hears about it, connected or not; each decides. */
    @Override public void markedRead(Conversation conversation) {
        for (CrmMessageTransport transport : transports) {
            if (transport.supports(conversation)) transport.markedRead(conversation);
        }
    }
}
