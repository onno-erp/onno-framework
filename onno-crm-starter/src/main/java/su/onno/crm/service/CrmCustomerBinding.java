package su.onno.crm.service;

import java.util.Objects;
import java.util.function.Function;
import su.onno.crm.domain.Channel;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

/** Binds CRM to the host's customer catalog and an optional inbound-contact creation policy. */
public record CrmCustomerBinding<T extends CatalogObject>(CrmCatalogBinding<T> catalog,
        Function<IncomingContact, Ref<T>> incoming) {
    public CrmCustomerBinding { Objects.requireNonNull(catalog); }
    public CrmCustomerBinding(CrmCatalogBinding<T> catalog) { this(catalog, null); }

    /** Provider identity, scoped to a connection. A host policy decides whether/how to create a record. */
    public record IncomingContact(String channel, String connectionKey, String externalId,
            String name, String email, String phone, String avatarUrl, String source) {}

    public Ref<T> resolve(IncomingContact contact) {
        if (incoming == null) throw new IllegalArgumentException("The host has not configured inbound customer resolution");
        Ref<T> ref = Objects.requireNonNull(incoming.apply(contact), "Inbound customer policy must return a saved reference");
        if (!ref.type().equals(catalog.type())) throw new IllegalArgumentException("Inbound customer policy returned another catalog type");
        catalog.require(ref.id());
        return ref;
    }
}
