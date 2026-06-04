package com.onec.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.ui.CatalogCommandService;
import com.onec.ui.CatalogQueryService;
import com.onec.ui.DocumentCommandService;
import com.onec.ui.DocumentQueryService;
import com.onec.ui.RegisterQueryService;
import com.onec.ui.UiAccessService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataToolFactoryTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final CatalogQueryService catalogQuery = mock(CatalogQueryService.class);
    private final DocumentQueryService documentQuery = mock(DocumentQueryService.class);
    private final RegisterQueryService registerQuery = mock(RegisterQueryService.class);
    private final CatalogCommandService catalogCommands = mock(CatalogCommandService.class);
    private final DocumentCommandService documentCommands = mock(DocumentCommandService.class);
    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper().findAndRegisterModules());

    private final CatalogDescriptor clients = new CatalogDescriptor(
            "Clients", "Clients", "cat_clients", Object.class, 12, false, true, "C-", "Rentals",
            List.of("RENTALS"), List.of("RENTALS"),
            List.of(new AttributeDescriptor("name", "Name", "name", String.class, 100,
                    true, false, "", 0, 0, true, true, true, 0, "", "", "",
                    AttributeDescriptor.Constraints.NONE, false)));

    MetadataToolFactoryTest() {
        registry.registerCatalog(clients);
    }

    private MetadataToolFactory factory(OnecMcpProperties props) {
        UiAccessService access = new UiAccessService(registry);
        return new MetadataToolFactory(registry, access, catalogQuery, documentQuery, registerQuery,
                catalogCommands, documentCommands, props, json);
    }

    private static Set<String> names(List<SyncToolSpecification> tools) {
        return tools.stream().map(t -> t.tool().name()).collect(Collectors.toSet());
    }

    private static SyncToolSpecification byName(List<SyncToolSpecification> tools, String name) {
        return tools.stream().filter(t -> t.tool().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void exposesFullToolSetByDefault() {
        Set<String> names = names(factory(new OnecMcpProperties()).build());
        assertThat(names).contains(
                "describe_metadata", "list_catalog", "get_catalog", "list_documents", "get_document",
                "register_balance", "register_movements",
                "create_catalog", "update_catalog", "delete_catalog",
                "create_document", "update_document", "delete_document",
                "posting_preview", "post_document", "unpost_document");
    }

    @Test
    void writeAndPostingTogglesRemoveTools() {
        OnecMcpProperties props = new OnecMcpProperties();
        props.setWritesEnabled(false);
        props.setPostingEnabled(false);
        Set<String> names = names(factory(props).build());
        assertThat(names).contains("list_catalog", "get_document");
        assertThat(names).doesNotContain(
                "create_catalog", "update_catalog", "delete_catalog",
                "create_document", "update_document", "delete_document",
                "posting_preview", "post_document", "unpost_document");
    }

    @Test
    void readToolDeniesAnonymousCaller() {
        when(catalogQuery.require("Clients")).thenReturn(clients);
        SyncToolSpecification listCatalog = byName(factory(new OnecMcpProperties()).build(), "list_catalog");

        // A null exchange yields a null principal -> deny by default in UiAccessService.
        CallToolResult result = listCatalog.callHandler()
                .apply(null, new CallToolRequest("list_catalog", Map.of("name", "Clients")));

        assertThat(result.isError()).isTrue();
    }

    @Test
    void describeMetadataHidesEntitiesFromAnonymousCaller() {
        SyncToolSpecification describe = byName(factory(new OnecMcpProperties()).build(), "describe_metadata");

        CallToolResult result = describe.callHandler()
                .apply(null, new CallToolRequest("describe_metadata", Map.of()));

        // Not an error, but the Clients catalog must not be disclosed to an anonymous caller.
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = result.content().toString();
        assertThat(text).doesNotContain("Clients");
    }
}
