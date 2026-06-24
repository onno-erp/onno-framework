package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link CatalogQueryService#rowsByIds} — the engine behind the list island's surgical single-row
 * live patch. It returns the requested live rows decorated exactly like a page (refs resolved), so
 * the client can swap one changed row in place without re-paging the whole window; deletion-marked
 * rows and unknown ids are simply absent.
 */
class CatalogRowsByIdsTest {

    @Catalog(name = "RbiCompanies")
    static class Company extends CatalogObject {
    }

    @Catalog(name = "RbiClients")
    static class Client extends CatalogObject {
        @Attribute(length = 120)
        private String fullName;
        @Attribute
        private Ref<Company> company;
    }

    private Jdbi jdbi;
    private CatalogQueryService service;
    private CatalogDescriptor clientDesc;
    private CatalogDescriptor companyDesc;

    private final UUID acme = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID grace = UUID.randomUUID();
    private final UUID linus = UUID.randomUUID();
    private final UUID deleted = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rowsbyids" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Company.class));
        registry.registerCatalog(scanner.scan(Client.class));
        new SchemaGenerator(registry).execute(jdbi);

        service = new CatalogQueryService(registry, jdbi);
        clientDesc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Client.class).findFirst().orElseThrow();
        companyDesc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Company.class).findFirst().orElseThrow();

        insertCompany(acme, "CO-1", "Acme Corp");
        insertClient(ada, "C-1", "Ada Lovelace", acme);
        insertClient(grace, "C-2", "Grace Hopper", acme);
        insertClient(linus, "C-3", "Linus Torvalds", acme);
        insertClient(deleted, "C-4", "Gone", acme);
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + clientDesc.tableName()
                        + " SET _deletion_mark = true WHERE _id = :id")
                .bind("id", deleted).execute());
    }

    @Test
    void returnsOnlyTheRequestedRows_withRefsResolved() {
        List<Map<String, Object>> rows = service.rowsByIds(clientDesc, List.of(ada, linus));

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("_description"))
                .containsExactlyInAnyOrder("Ada Lovelace", "Linus Torvalds");
        // The ref is resolved to the company's description, just like a paged row.
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get(companyColumn() + "_display")).isEqualTo("Acme Corp"));
    }

    @Test
    void skipsDeletionMarkedAndUnknownIds() {
        List<Map<String, Object>> rows = service.rowsByIds(clientDesc, List.of(ada, deleted, UUID.randomUUID()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("_description")).isEqualTo("Ada Lovelace");
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertThat(service.rowsByIds(clientDesc, List.of())).isEmpty();
        assertThat(service.rowsByIds(clientDesc, null)).isEmpty();
    }

    private String companyColumn() {
        return clientDesc.attributes().stream()
                .filter(a -> a.fieldName().equals("company"))
                .map(AttributeDescriptor::columnName)
                .findFirst().orElseThrow();
    }

    private void insertCompany(UUID id, String code, String description) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + companyDesc.tableName()
                        + " (_id, _code, _description) VALUES (:id, :code, :description)")
                .bind("id", id).bind("code", code).bind("description", description).execute());
    }

    private void insertClient(UUID id, String code, String description, UUID company) {
        String fullNameCol = clientDesc.attributes().stream()
                .filter(a -> a.fieldName().equals("fullName"))
                .map(AttributeDescriptor::columnName).findFirst().orElseThrow();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + clientDesc.tableName()
                        + " (_id, _code, _description, " + fullNameCol + ", " + companyColumn() + ")"
                        + " VALUES (:id, :code, :description, :fullName, :company)")
                .bind("id", id).bind("code", code).bind("description", description)
                .bind("fullName", description).bind("company", company).execute());
    }
}
