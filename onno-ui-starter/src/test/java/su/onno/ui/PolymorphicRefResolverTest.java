package su.onno.ui;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
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
import su.onno.security.SecretCipher;
import su.onno.types.PolyRef;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolymorphicRefResolverTest {

    @Catalog(name = "ResolverAccounts")
    static class Account extends CatalogObject {
    }

    @Document(name = "ResolverPayments")
    static class Payment extends DocumentObject {
    }

    @Catalog(name = "ResolverLinks")
    static class Link extends CatalogObject {
        @Attribute
        @RefTargets({Account.class, Payment.class})
        private PolyRef subject;
    }

    @Test
    void resolvesEachStoredConcreteTargetAndEmitsDynamicLinkMetadata() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(dataSource);
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        var accountDescriptor = scanner.scan(Account.class);
        var paymentDescriptor = scanner.scanDocument(Payment.class);
        var linkDescriptor = scanner.scan(Link.class);
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(accountDescriptor);
        registry.registerDocument(paymentDescriptor);
        registry.registerCatalog(linkDescriptor);

        @SuppressWarnings("unchecked")
        Map<String, Object> subjectMetadata =
                ((List<Map<String, Object>>) new ResolvedMetadataService(
                        registry, new FieldHintResolver(List.of()))
                        .describeCatalog(linkDescriptor).get("attributes")).getFirst();
        assertThat(subjectMetadata)
                .containsEntry("isRef", true)
                .containsEntry("isPolymorphicRef", true);
        assertThat((List<Map<String, Object>>) subjectMetadata.get("refTargets"))
                .extracting(target -> target.get("kind"), target -> target.get("name"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("catalog", "ResolverAccounts"),
                        org.assertj.core.groups.Tuple.tuple("document", "ResolverPayments"));

        UUID accountId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE " + accountDescriptor.tableName()
                    + " (_id UUID PRIMARY KEY, _code VARCHAR(20), _description VARCHAR(200))");
            handle.execute("CREATE TABLE " + paymentDescriptor.tableName()
                    + " (_id UUID PRIMARY KEY, _number VARCHAR(20))");
            handle.createUpdate("INSERT INTO " + accountDescriptor.tableName()
                            + " (_id, _code, _description) VALUES (:id, 'A-1', 'Main bank')")
                    .bind("id", accountId).execute();
            handle.createUpdate("INSERT INTO " + paymentDescriptor.tableName()
                            + " (_id, _number) VALUES (:id, 'PAY-7')")
                    .bind("id", paymentId).execute();
        });

        AttributeDescriptor subject = linkDescriptor.attributes().getFirst();
        Map<String, Object> accountRow = new HashMap<>();
        accountRow.put(subject.columnName(), PolyRef.of(Account.class, accountId).externalForm());
        Map<String, Object> paymentRow = new HashMap<>();
        paymentRow.put(subject.columnName(), PolyRef.of(Payment.class, paymentId).externalForm());

        new RefResolver(registry, jdbi)
                .resolveAttributes(List.of(accountRow, paymentRow), List.of(subject));

        assertThat(accountRow.get("subject_display")).isEqualTo("Main bank");
        assertThat(accountRow.get("subject_ref")).isEqualTo(Map.of(
                "id", accountId.toString(),
                "type", "ResolverAccounts",
                "kind", "catalog",
                "javaType", Account.class.getName(),
                "display", "Main bank"));
        assertThat(paymentRow.get("subject_display")).isEqualTo("PAY-7");
        assertThat(paymentRow.get("subject_ref")).isEqualTo(Map.of(
                "id", paymentId.toString(),
                "type", "ResolverPayments",
                "kind", "document",
                "javaType", Payment.class.getName(),
                "display", "PAY-7"));
    }

    @Test
    void writeBindingAcceptsLogicalTargetObjectAndRejectsTargetsOutsideAllowlist() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        AttributeDescriptor subject = scanner.scan(Link.class).attributes().getFirst();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(dataSource);
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE poly_write (subject VARCHAR(512))"));
        UUID accountId = UUID.randomUUID();

        jdbi.useHandle(handle -> {
            var update = handle.createUpdate("INSERT INTO poly_write (subject) VALUES (:subject)");
            EntityWriteSupport.bindAttribute(update, subject,
                    Map.of("type", "ResolverAccounts", "id", accountId.toString()),
                    new SecretCipher(null));
            update.execute();
        });

        String stored = jdbi.withHandle(handle ->
                handle.createQuery("SELECT subject FROM poly_write").mapTo(String.class).one());
        assertThat(stored)
                .isEqualTo(Account.class.getName() + "|" + accountId);
        assertThatThrownBy(() -> jdbi.useHandle(handle -> {
            var update = handle.createUpdate("INSERT INTO poly_write (subject) VALUES (:subject)");
            EntityWriteSupport.bindAttribute(
                    update, subject, PolyRef.of(String.class, UUID.randomUUID()),
                    new SecretCipher(null));
            update.execute();
        }))
                .hasMessageContaining("is not allowed for subject");
    }
}
