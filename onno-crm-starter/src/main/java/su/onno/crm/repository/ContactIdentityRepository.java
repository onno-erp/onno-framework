package su.onno.crm.repository;
import java.util.List;
import su.onno.crm.domain.ContactIdentity;
import su.onno.repository.CatalogRepository;
import su.onno.types.Ref;
public interface ContactIdentityRepository extends CatalogRepository<ContactIdentity> {
    List<ContactIdentity> findByCustomerAndDeletionMarkFalse(java.util.UUID customer);
    /** Every listed customer's identities in one read, for rendering a page of rows. */
    List<ContactIdentity> findByCustomerInAndDeletionMarkFalse(java.util.Collection<java.util.UUID> customers);
}
