package com.example.ui.views;

import com.example.domain.catalogs.Client;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;

import org.springframework.stereotype.Component;

/**
 * Declares the Client catalog as a visible surface and its field order. List
 * columns fall back to the auto-generated defaults.
 */
@Component
public class ClientView implements EntityView {

    @Override
    public Class<?> entity() {
        return Client.class;
    }

    /** Opt this catalog into the per-entity comment thread (off by default elsewhere — e.g. Bills,
     *  Payments, Bank Accounts carry no comments), so staff can keep notes on a guest. */
    @Override
    public boolean comments() {
        return true;
    }

    @Override
    public void fields(EntityConfigBuilder f) {
        f.field("firstName").order(0)
                .field("lastName1").order(1)
                .field("lastName2").order(2)
                        .hint("Second surname — common in Spanish/Latin American names; leave blank if not applicable.")
                .field("gender").order(3)
                .field("birthday").order(4)
                .field("docType").order(5)
                        .hint("Government ID type (Passport, DNI, NIE, …) recorded at check-in.")
                .field("docNumber").order(6)
                        .hint("ID number exactly as printed; reported to the police lodging registry.")
                .field("docIssuedOn").order(7)
                .field("nationality").order(8)
                .field("address").order(9)
                .field("city").order(10)
                .field("postCode").order(11)
                .field("country").order(12)
                .field("email").order(13)
                        .hint("Used for booking confirmations and the guest-portal invite.")
                .field("mobile").order(14);
    }
}
