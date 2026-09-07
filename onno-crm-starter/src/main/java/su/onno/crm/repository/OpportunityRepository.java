package su.onno.crm.repository;

import su.onno.crm.domain.Opportunity;
import su.onno.repository.CatalogRepository;

public interface OpportunityRepository extends CatalogRepository<Opportunity> {
    java.util.List<Opportunity> findByCustomerAndDeletionMarkFalse(su.onno.types.Ref<su.onno.crm.domain.Customer> customer);
}
