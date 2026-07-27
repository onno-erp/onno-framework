package su.onno.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.RefTargets;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.PolyRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolymorphicReferenceMetadataTest {

    @Catalog(name = "PolyBankAccounts", title = "Bank accounts")
    static class BankAccount extends CatalogObject {
    }

    @Document(name = "PolyCashOrders", title = "Cash orders")
    static class CashOrder extends DocumentObject {
    }

    @Document(name = "PolyPayments")
    static class Payment extends DocumentObject {
        @Attribute(required = true)
        @RefTargets({BankAccount.class, CashOrder.class})
        private PolyRef settlementTarget;
    }

    @Document(name = "PolyMissingTargets")
    static class MissingTargets extends DocumentObject {
        @Attribute
        private PolyRef target;
    }

    @Document(name = "PolyInvalidTargets")
    static class InvalidTargets extends DocumentObject {
        @Attribute
        @RefTargets(String.class)
        private PolyRef target;
    }

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scan_exposesExplicitCatalogAndDocumentTargets() {
        AttributeDescriptor target = scanner.scanDocument(Payment.class).attributes().getFirst();

        assertThat(target.isRef()).isTrue();
        assertThat(target.isPolymorphicRef()).isTrue();
        assertThat(target.refTarget()).isNull();
        assertThat(target.length()).isEqualTo(512);
        assertThat(target.refTargets())
                .extracting(ReferenceTargetDescriptor::kind,
                        ReferenceTargetDescriptor::logicalName,
                        ReferenceTargetDescriptor::displayTitle,
                        ReferenceTargetDescriptor::javaTypeName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "catalog", "PolyBankAccounts", "Bank accounts",
                                BankAccount.class.getName()),
                        org.assertj.core.groups.Tuple.tuple(
                                "document", "PolyCashOrders", "Cash orders",
                                CashOrder.class.getName()));
    }

    @Test
    void scan_rejectsMissingOrInvalidTargetAllowlist() {
        assertThatThrownBy(() -> scanner.scanDocument(MissingTargets.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare @RefTargets");
        assertThatThrownBy(() -> scanner.scanDocument(InvalidTargets.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be annotated with @Catalog or @Document");
    }
}
