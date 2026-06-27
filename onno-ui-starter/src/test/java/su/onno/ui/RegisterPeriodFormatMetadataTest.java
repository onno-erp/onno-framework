package su.onno.ui;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A register's view can format its movement-timestamp column with {@code field("period").format(…)}
 * — the register analogue of a document's {@code _date} system column. The hint surfaces as
 * {@code periodFormat} in the resolved register metadata (where the register surface picks it up for
 * the {@code _period} column); a resource hint still formats its own column. No hint → blank.
 */
class RegisterPeriodFormatMetadataTest {

    @AccumulationRegister(name = "PfSales", type = AccumulationType.TURNOVER)
    static class PfSales extends AccumulationRecord {
        @Dimension private UUID item;                              // plain UUID → no ref resolution
        @Resource(precision = 14, scale = 2) private BigDecimal revenue;
    }

    static class PfSalesView implements EntityView {
        @Override public Class<?> entity() {
            return PfSales.class;
        }

        @Override public void fields(EntityConfigBuilder f) {
            f.field("period").format("dd-MM-yyyy")
                    .field("revenue").format("currency:USD");
        }
    }

    private Map<String, Object> describe(EntityView... views) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(PfSales.class));
        ResolvedMetadataService svc =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(views)));
        return svc.describeRegister(scanner.scanRegister(PfSales.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resource(Map<String, Object> reg, String fieldName) {
        List<Map<String, Object>> resources = (List<Map<String, Object>>) reg.get("resources");
        return resources.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @Test
    void periodHint_surfacesAsPeriodFormat_andResourceStillFormats() {
        Map<String, Object> reg = describe(new PfSalesView());
        assertThat(reg.get("periodFormat")).isEqualTo("dd-MM-yyyy");
        assertThat(resource(reg, "revenue").get("format")).isEqualTo("currency:USD");
    }

    @Test
    void noView_periodFormatBlank() {
        assertThat(describe().get("periodFormat")).isEqualTo("");
    }
}
