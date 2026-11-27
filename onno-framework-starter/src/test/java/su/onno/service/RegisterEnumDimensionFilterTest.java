package su.onno.service;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Enumeration;
import su.onno.annotations.Resource;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.repository.EnumerationPersistence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A register's enum dimension filter takes the constant name as well as the stored UUID. */
class RegisterEnumDimensionFilterTest {

    @Enumeration(name = "RedfChannels")
    public enum Channel { WEB, STORE }

    @AccumulationRegister(name = "RedfSales", type = AccumulationType.TURNOVER)
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class SalesRecord extends AccumulationRecord {
        @Dimension private Channel channel;
        @Resource(precision = 15, scale = 2) private BigDecimal amount;
    }

    private final AccumulationRegisterDescriptor sales;
    private final RegisterQueryService service;

    RegisterEnumDimensionFilterTest() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerEnumeration(scanner.scanEnumeration(Channel.class));
        sales = scanner.scanRegister(SalesRecord.class);
        registry.registerAccumulation(sales);
        // Compiling a dimension filter never touches the database.
        service = new RegisterQueryService(registry, null);
    }

    @Test
    void constantNameAndUuidBindTheSameStoredId() {
        UUID store = EnumerationPersistence.resolveId(Channel.class, Channel.STORE);

        assertThat(service.dimensionFilters(sales, Map.of("channel", "STORE")).bindings())
                .containsValue(store);
        assertThat(service.dimensionFilters(sales, Map.of("channel", store.toString())).bindings())
                .containsValue(store);
    }

    @Test
    void unknownNameIsABadRequestNamingTheConstants() {
        assertThatThrownBy(() -> service.dimensionFilters(sales, Map.of("channel", "PHONE")))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.status()).isEqualTo(ServiceException.Status.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("channel", "PHONE", "WEB, STORE");
                });
    }
}
