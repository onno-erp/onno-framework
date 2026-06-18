package su.onno.repository;

import su.onno.fixtures.TestCompanyName;
import su.onno.metadata.*;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ConstantPersistenceTest {

    private Jdbi jdbi;
    private ConstantPersistence persistence;
    private ConstantDescriptor companyNameDesc;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:constanttest" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        companyNameDesc = scanner.scanConstant(TestCompanyName.class);
        registry.registerConstant(companyNameDesc);

        new SchemaGenerator(registry).execute(jdbi);

        persistence = new ConstantPersistence(jdbi);
    }

    @Test
    void get_unsetConstant_returnsNull() {
        Object value = persistence.get(companyNameDesc);
        assertThat(value).isNull();
    }

    @Test
    void setAndGet_roundTrips() {
        persistence.set(companyNameDesc, "Acme Corp");

        Object value = persistence.get(companyNameDesc);
        assertThat(value).isEqualTo("Acme Corp");
    }

    @Test
    void set_overwritesPreviousValue() {
        persistence.set(companyNameDesc, "Old Name");
        persistence.set(companyNameDesc, "New Name");

        Object value = persistence.get(companyNameDesc);
        assertThat(value).isEqualTo("New Name");
    }

    @Test
    void constantManager_getAndSet() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerConstant(companyNameDesc);
        ConstantManager manager = new ConstantManager(persistence, registry);

        manager.set(TestCompanyName.class, "Test Corp");
        String value = manager.get(TestCompanyName.class);

        assertThat(value).isEqualTo("Test Corp");
    }
}
