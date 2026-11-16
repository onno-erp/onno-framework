package su.onno.service;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAggregatesTest {

    @Document(name = "RevenueOrders")
    static class Order extends DocumentObject {
        @Attribute(precision = 15, scale = 2)
        private BigDecimal total;
    }

    @Test
    void postedRevenueIncludesOnlyPostedOrders() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        var registry = new MetadataRegistry();
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        var descriptor = scanner.scanDocument(Order.class);
        registry.registerDocument(descriptor);
        new SchemaGenerator(registry).execute(jdbi);

        jdbi.useHandle(handle -> {
            String insert = "INSERT INTO " + descriptor.tableName()
                    + " (_id, _posted, total) VALUES (:id, :posted, :total)";

            handle.createUpdate(insert)
                    .bind("id", UUID.randomUUID())
                    .bind("posted", true)
                    .bind("total", new BigDecimal("100"))
                    .execute();

            handle.createUpdate(insert)
                    .bind("id", UUID.randomUUID())
                    .bind("posted", false)
                    .bind("total", new BigDecimal("250"))
                    .execute();
        });

        var query = new DocumentQueryService(registry, jdbi);

        BigDecimal allRevenue = query.aggregate(
                AccessSubject.system(), descriptor,
                "sum", "total", null);

        BigDecimal postedRevenue = query.aggregate(
                AccessSubject.system(), descriptor,
                "sum", "total", "posted = true");

        assertThat(allRevenue).isEqualByComparingTo("350");
        assertThat(postedRevenue).isEqualByComparingTo("100");
    }
}