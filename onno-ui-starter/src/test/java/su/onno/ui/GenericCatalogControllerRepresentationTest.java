package su.onno.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GenericCatalogControllerRepresentationTest {

    private final CatalogQueryService query = mock(CatalogQueryService.class);
    private final UiAccessService access = mock(UiAccessService.class);
    private final CatalogCommandService commands = mock(CatalogCommandService.class);
    private final RelatedListReader relatedLists = mock(RelatedListReader.class);
    private final UiMessages messages = mock(UiMessages.class);
    private final BatchRunner batch = mock(BatchRunner.class);

    private final CatalogDescriptor descriptor =
            new MetadataScanner(new DefaultNamingStrategy()).scan(Customer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new GenericCatalogController(query, access, commands, relatedLists, messages, batch))
                .build();
    }

    @Test
    void getDefaultsToLogicalRepresentation() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.require("customers")).thenReturn(descriptor);
        when(query.get(descriptor, id)).thenReturn(row(id));

        String json = mvc.perform(get("/api/catalogs/customers/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = objectMapper.readValue(json, new TypeReference<>() {});
        assertThat(body)
                .containsEntry("id", id.toString())
                .containsEntry("description", "Acme")
                .containsEntry("taxId", "123")
                .doesNotContainKeys("_id", "tax_id");
    }

    @Test
    void storageRepresentationPreservesLegacyResponse() throws Exception {
        UUID id = UUID.randomUUID();
        when(query.require("customers")).thenReturn(descriptor);
        when(query.get(descriptor, id)).thenReturn(row(id));

        String json = mvc.perform(get("/api/catalogs/customers/{id}", id)
                        .param("representation", "storage"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = objectMapper.readValue(json, new TypeReference<>() {});
        assertThat(body)
                .containsEntry("_id", id.toString())
                .containsEntry("_description", "Acme")
                .containsEntry("tax_id", "123")
                .doesNotContainKeys("id", "taxId");
    }

    @Test
    void invalidWriteRepresentationIsRejectedBeforeTheCommandRuns() throws Exception {
        when(query.require("customers")).thenReturn(descriptor);

        mvc.perform(post("/api/catalogs/customers")
                        .param("representation", "v3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taxId\":\"123\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commands);
    }

    private static Map<String, Object> row(UUID id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_id", id);
        row.put("_description", "Acme");
        row.put("tax_id", "123");
        return row;
    }

    @Catalog(name = "Customers")
    static class Customer extends CatalogObject {
        @Attribute(name = "tax_id")
        private String taxId;
    }
}
