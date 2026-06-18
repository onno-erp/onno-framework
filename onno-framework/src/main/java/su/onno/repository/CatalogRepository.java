package su.onno.repository;

import su.onno.model.CatalogObject;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface CatalogRepository<T extends CatalogObject> extends ListCrudRepository<T, UUID> {

    Optional<T> findByCode(String code);
}
