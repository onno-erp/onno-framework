package su.onno.service;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.RefTargets;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.PolyRef;
import su.onno.validation.ValidationErrors;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityBodyMapperTest {

    @Catalog(name = "MapperAccounts")
    static class Account extends CatalogObject {
    }

    @Document(name = "MapperPayments")
    static class Payment extends DocumentObject {
    }

    @Catalog(name = "MapperLinks")
    static class Link extends CatalogObject {
        @Attribute
        @RefTargets({Account.class, Payment.class})
        private PolyRef subject;

        @Attribute
        private Integer count;
    }

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
    private final List<AttributeDescriptor> attributes = scanner.scan(Link.class).attributes();
    private final EntityBodyMapper mapper = new EntityBodyMapper(new MetadataRegistry());

    @Test
    void polymorphicRefAcceptsLogicalTargetObjectAndRejectsTargetsOutsideAllowlist() {
        UUID accountId = UUID.randomUUID();
        Link link = new Link();
        ValidationErrors errors = new ValidationErrors();

        mapper.applyBody(link, attributes,
                Map.of("subject", Map.of("type", "MapperAccounts", "id", accountId.toString())), errors);

        assertThat(errors.isEmpty()).isTrue();
        assertThat(link.subject.externalForm()).isEqualTo(Account.class.getName() + "|" + accountId);
        assertThatThrownBy(() -> mapper.applyBody(link, attributes,
                Map.of("subject", PolyRef.of(String.class, UUID.randomUUID())), new ValidationErrors()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("is not allowed for subject");
    }

    @Test
    void valueThatDoesNotCoerceIsAFieldError() {
        Link link = new Link();
        ValidationErrors errors = new ValidationErrors();

        mapper.applyBody(link, attributes, Map.of("count", "many"), errors);

        assertThat(errors.fieldErrors()).containsKey("count");
        assertThat(link.count).isNull();
    }
}
