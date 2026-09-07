package su.onno.crm.repository;
import java.util.List;
import su.onno.crm.domain.ContactIdentity;
import su.onno.crm.domain.Customer;
import su.onno.repository.CatalogRepository;
import su.onno.types.Ref;
public interface ContactIdentityRepository extends CatalogRepository<ContactIdentity> {
    List<ContactIdentity> findByCustomerAndDeletionMarkFalse(Ref<Customer> customer);
}
