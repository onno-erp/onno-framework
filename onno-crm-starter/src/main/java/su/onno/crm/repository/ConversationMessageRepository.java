package su.onno.crm.repository;

import java.util.List;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.repository.CatalogRepository;
import su.onno.types.Ref;

public interface ConversationMessageRepository extends CatalogRepository<ConversationMessage> {
    List<ConversationMessage> findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
            Ref<Conversation> conversation);
}
