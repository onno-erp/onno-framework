package su.onno.crm.service;

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

    private Route resolve(Conversation conversation) {
        Route selected = null;
        Connection unavailable = new Connection(false, "Messaging channel is not connected", 8000);
        for (CrmMessageTransport transport : transports) {
            Connection connection = transport.connection(conversation);
            if (!connection.connected()) {
                if (transports.size() == 1) unavailable = connection;
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
        return resolve(conversation).connection();
    }

    @Override public void enqueue(Conversation conversation, ConversationMessage message) {
        Route route = resolve(conversation);
        if (route.transport() == null) throw new IllegalArgumentException(route.connection().label());
        route.transport().enqueue(conversation, message);
    }
}
