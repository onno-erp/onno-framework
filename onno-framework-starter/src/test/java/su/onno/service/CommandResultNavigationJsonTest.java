package su.onno.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.annotations.Document;
import su.onno.command.CommandRegistry;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.metadata.CatalogDescriptor;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A command's {@code navigateTo} reaches every client through the JSON the command endpoint returns. */
class CommandResultNavigationJsonTest {

    private final ObjectMapper json = new ObjectMapper();
    private final BatchRunner batch = new BatchRunner(1);

    @AfterEach
    void close() {
        batch.destroy();
    }

    @Test
    void collectionCommandReturnsTheCreatedRecordRouteInItsJsonResponse() throws Exception {
        UUID created = UUID.fromString("00000000-0000-0000-0000-000000000449");
        AccessSubject subject = AccessSubject.user("alice", Set.of("OPERATOR"));
        Commands<String> bean = new Commands<>() {
            @Override public Class<String> entity() { return String.class; }
            @Override public void define(CommandSpec<String> c) {
                c.collectionCommand("createInvoice").run(ctx -> CommandResult.open(Ref.of(Invoice.class, created)));
            }
        };
        CatalogQueryService catalogs = mock(CatalogQueryService.class);
        CatalogDescriptor descriptor = mock(CatalogDescriptor.class);
        when(catalogs.require("customers")).thenReturn(descriptor);
        doReturn(String.class).when(descriptor).javaClass();
        CommandService service = new CommandService(catalogs, mock(DocumentQueryService.class),
                mock(AccessService.class), new CommandRegistry(List.of(bean)), new WebProperties(), batch);

        CommandResult result = service.run("catalogs", "customers", "createInvoice", null, Map.of(), subject);
        var body = json.readTree(json.writeValueAsString(result));
        assertThat(body.path("navigateTo").asText()).isEqualTo("/documents/sales_invoices/" + created);
        assertThat(body.path("refresh").asBoolean()).isFalse();
        assertThat(body.path("feedback").isNull()).isTrue();
    }

    @Test
    void readsOldResponsesAndRoundTripsCombinedResults() throws Exception {
        CommandResult old = json.readValue("{\"refresh\":true,\"feedback\":null}", CommandResult.class);
        assertThat(old).isEqualTo(CommandResult.reload());
        CommandResult combined = CommandResult.success("Created").andRefresh().andNavigate("/reports");
        assertThat(json.readValue(json.writeValueAsString(combined), CommandResult.class)).isEqualTo(combined);
    }

    @Document(name = "SalesInvoices")
    static class Invoice extends DocumentObject {}
}
