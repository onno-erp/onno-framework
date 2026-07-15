package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ref-picker typeahead accepts a {@code filter} predicate (the cascading {@code refFilter},
 * already resolved by the client) and narrows its results server-side — with the uuid value bound
 * typed against the ref column, and still composing with the text search.
 */
class CatalogSearchRefFilterTest {

    @Catalog(name = "CascadeSuppliers")
    static class Supplier extends CatalogObject {
    }

    @Catalog(name = "CascadeBooks")
    static class Book extends CatalogObject {
        @Attribute
        private Ref<Supplier> supplier;
    }

    private Jdbi jdbi;
    private CatalogQueryService query;
    private CatalogDescriptor supplierDesc;
    private CatalogDescriptor bookDesc;
    private final UUID north = UUID.randomUUID();
    private final UUID south = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        supplierDesc = scanner.scan(Supplier.class);
        registry.registerCatalog(supplierDesc);
        bookDesc = scanner.scan(Book.class);
        registry.registerCatalog(bookDesc);
        new SchemaGenerator(registry).execute(jdbi);

        supplier(north, "North Books");
        supplier(south, "South Books");
        book("Alpha Atlas", north);
        book("Beta Guide", north);
        book("Alpha Almanac", south);

        query = new CatalogQueryService(registry, jdbi);
    }

    @Test
    void searchNarrowsByRefFilter() {
        List<Map<String, Object>> rows = query.search(bookDesc, null, 50, "supplier = " + north);
        assertThat(rows).extracting(r -> r.get("_description"))
                .containsExactlyInAnyOrder("Alpha Atlas", "Beta Guide");
    }

    @Test
    void refFilterComposesWithTextSearch() {
        List<Map<String, Object>> rows = query.search(bookDesc, "alpha", 50, "supplier = " + north);
        assertThat(rows).extracting(r -> r.get("_description"))
                .containsExactly("Alpha Atlas");
    }

    @Test
    void blankFilterLeavesSearchUnfiltered() {
        assertThat(query.search(bookDesc, null, 50, null)).hasSize(3);
        assertThat(query.search(bookDesc, null, 50, "")).hasSize(3);
    }

    private void supplier(UUID id, String name) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + supplierDesc.tableName() + " (_id, _code, _description, _deletion_mark, _is_folder, _version) "
                                + "VALUES (:id, '', :name, false, false, 0)")
                .bind("id", id).bind("name", name).execute());
    }

    private void book(String title, UUID supplierId) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + bookDesc.tableName() + " (_id, _code, _description, _deletion_mark, _is_folder, _version, supplier) "
                                + "VALUES (:id, '', :name, false, false, 0, :supplier)")
                .bind("id", UUID.randomUUID()).bind("name", title).bind("supplier", supplierId).execute());
    }
}
