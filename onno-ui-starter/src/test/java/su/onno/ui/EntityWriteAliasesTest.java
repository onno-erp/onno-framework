package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.security.SecretRedactor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityWriteAliasesTest {

    private final CatalogDescriptor customer = new su.onno.metadata.MetadataScanner(
            new su.onno.metadata.DefaultNamingStrategy()).scan(EntityJsonRepresentationTest.Customer.class);
    private final DocumentDescriptor order = new su.onno.metadata.MetadataScanner(
            new su.onno.metadata.DefaultNamingStrategy()).scanDocument(EntityJsonRepresentationTest.Order.class);

    @Test
    void catalogAcceptsStorageSystemAndAttributeAliases() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_code", "C-1");
        body.put("_description", "Acme");
        body.put("_is_folder", true);
        body.put("_parent", "9b55c5de-06b4-48ae-8590-99cde1ce237b");
        body.put("_version", 3);
        body.put("tax_id", "123");
        body.put("api_key", SecretRedactor.SET);

        Map<String, Object> normalized = EntityWriteAliases.catalog(customer, body);

        assertThat(normalized)
                .containsEntry("code", "C-1")
                .containsEntry("description", "Acme")
                .containsEntry("folder", true)
                .containsEntry("parent", "9b55c5de-06b4-48ae-8590-99cde1ce237b")
                .containsEntry("version", 3)
                .containsEntry("taxId", "123")
                .containsEntry("apiKey", SecretRedactor.SET)
                .doesNotContainKeys("_code", "_description", "_is_folder", "_parent", "_version",
                        "tax_id", "api_key");
        assertThat(body).containsKey("tax_id").as("the caller's map is not mutated");
    }

    @Test
    void documentAcceptsStorageAliasesInsideTabularRows() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("unit_price", new BigDecimal("19.95"));
        line.put("product_id", "e7f195d2-4c99-4364-abfc-00454402207c");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_number", "SO-1");
        body.put("_date", "2026-08-03T10:15:00");
        body.put("_version", 7);
        body.put("customer_id", "a2735a38-f44a-49dc-a249-afcf96c61b9e");
        body.put("items", List.of(line));

        Map<String, Object> normalized = EntityWriteAliases.document(order, body);

        assertThat(normalized)
                .containsEntry("number", "SO-1")
                .containsEntry("date", "2026-08-03T10:15:00")
                .containsEntry("version", 7)
                .containsEntry("customer", "a2735a38-f44a-49dc-a249-afcf96c61b9e");
        assertThat(rows(normalized).getFirst())
                .containsEntry("unitPrice", new BigDecimal("19.95"))
                .containsEntry("product", "e7f195d2-4c99-4364-abfc-00454402207c")
                .doesNotContainKeys("unit_price", "product_id");
    }

    @Test
    void equalCanonicalAndStorageValuesAreAccepted() {
        Map<String, Object> normalized = EntityWriteAliases.catalog(customer,
                Map.of("taxId", "123", "tax_id", "123"));

        assertThat(normalized).containsOnlyKeys("taxId").containsEntry("taxId", "123");
    }

    @Test
    void conflictingTopLevelAliasesAreRejected() {
        assertConflict(() -> EntityWriteAliases.catalog(customer,
                Map.of("taxId", "canonical", "tax_id", "storage")), "taxId", "tax_id");
    }

    @Test
    void conflictingTabularRowAliasesAreRejected() {
        assertConflict(() -> EntityWriteAliases.document(order,
                Map.of("items", List.of(Map.of("unitPrice", 10, "unit_price", 20)))),
                "unitPrice", "unit_price");
    }

    private static void assertConflict(Runnable action, String canonical, String storage) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(canonical)
                .hasMessageContaining(storage);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }
}
