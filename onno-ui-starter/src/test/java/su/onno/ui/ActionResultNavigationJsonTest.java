package su.onno.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionResultNavigationJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void entityHandlerReturnsTheCreatedRecordRouteInItsJsonResponse() throws Exception {
        UUID created = UUID.fromString("00000000-0000-0000-0000-000000000449");
        AccessSubject subject = AccessSubject.user("alice", Set.of("OPERATOR"));
        Principal principal = () -> "alice";
        EntityView<Customer> view = new EntityView<>() {
            @Override public Class<Customer> entity() { return Customer.class; }
            @Override public void actions(ActionSpec actions) {
                Function<ActionContext, ActionResult> handler = ctx -> {
                    assertThat(ctx.subject()).isEqualTo(subject);
                    assertThat(ctx.user()).isEqualTo("alice");
                    return ActionResult.open(Ref.of(Invoice.class, created));
                };
                actions.action("createInvoice").scope(ActionScope.TOOLBAR).handler(handler);
            }
        };
        CatalogQueryService catalogs = mock(CatalogQueryService.class);
        when(catalogs.require("customers")).thenReturn(
                new MetadataScanner(new DefaultNamingStrategy()).scan(Customer.class));
        BatchRunner batch = new BatchRunner(1);
        try {
            ActionController controller = new ActionController(catalogs, mock(DocumentQueryService.class),
                    mock(UiAccessService.class), new UiActionResolver(List.of(view)), new UiProperties(), batch);
            ActionResult result = controller.run("catalogs", "customers", "createInvoice", null,
                    Map.of(), principal, subject);
            var body = json.readTree(json.writeValueAsString(result));
            assertThat(body.path("navigateTo").asText()).isEqualTo("/documents/sales_invoices/" + created);
            assertThat(body.path("refresh").asBoolean()).isFalse();
            assertThat(body.path("feedback").isNull()).isTrue();
        } finally {
            batch.destroy();
        }
    }

    @Test
    void readsOldResponsesAndRoundTripsCombinedResults() throws Exception {
        ActionResult old = json.readValue("{\"refresh\":true,\"feedback\":null}", ActionResult.class);
        assertThat(old).isEqualTo(ActionResult.reload());
        ActionResult combined = new ActionResult(true, ActionToast.success("Created").feedback(), "/reports");
        assertThat(json.readValue(json.writeValueAsString(combined), ActionResult.class)).isEqualTo(combined);
    }

    @Catalog(name = "Customers")
    static class Customer extends CatalogObject {}

    @Document(name = "SalesInvoices")
    static class Invoice extends DocumentObject {}
}
