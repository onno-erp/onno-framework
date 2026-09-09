package com.example.crm.repository;

import com.example.crm.domain.Opportunity;
import su.onno.repository.CatalogRepository;

public interface OpportunityRepository extends CatalogRepository<Opportunity> {
    java.util.List<Opportunity> findByCustomerAndDeletionMarkFalse(su.onno.types.Ref<com.example.domain.catalogs.Customer> customer);
}
