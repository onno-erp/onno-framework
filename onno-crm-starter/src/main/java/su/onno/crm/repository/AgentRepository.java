package su.onno.crm.repository;

import java.util.Optional;
import su.onno.crm.domain.Agent;
import su.onno.repository.CatalogRepository;

public interface AgentRepository extends CatalogRepository<Agent> {
    Optional<Agent> findByEmailIgnoreCaseAndDeletionMarkFalse(String email);
}
