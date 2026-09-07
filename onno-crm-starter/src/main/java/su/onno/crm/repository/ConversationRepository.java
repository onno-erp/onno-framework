package su.onno.crm.repository;

import su.onno.crm.domain.Conversation;
import su.onno.repository.CatalogRepository;

public interface ConversationRepository extends CatalogRepository<Conversation> {
    java.util.List<Conversation> findByCustomerAndDeletionMarkFalse(su.onno.types.Ref<su.onno.crm.domain.Customer> customer);
}
