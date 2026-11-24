package su.onno.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.access.AccessSubject;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.service.AccessService;
import su.onno.service.CatalogCommandService;
import su.onno.service.CatalogQueryService;
import su.onno.service.TestEntityStore;
import su.onno.service.WebProperties;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies field errors from real callback failures cross the HTTP boundary as 400, not 500. */
class CatalogHierarchyHttpTest {

    @Catalog(name = "HttpHierarchy", hierarchical = true)
    public static class Node extends CatalogObject { }

    private TestEntityStore.Stack stack;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(Node.class));
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        stack = TestEntityStore.builder(dataSource, registry).build();
        new SchemaGenerator(registry).execute(stack.jdbi());
        var query = new CatalogQueryService(registry, stack.jdbi());
        var access = new AccessService(registry);
        var commands = new CatalogCommandService(registry, stack.jdbi(), new WebProperties(), query, access, stack.store());
        mvc = MockMvcBuilders.standaloneSetup(new GenericCatalogController(query, access, commands,
                        new UiMessages(Map.of()), null))
                .setControllerAdvice(new OnnoValidationExceptionHandler(), new ServiceExceptionHandler())
                .setCustomArgumentResolvers(AccessSubjectArgumentResolver.fixed(AccessSubject.system()))
                .build();
    }

    @AfterEach void close() { stack.close(); }

    @Test void missingOrMalformedParentOnCreateHasAFieldErrorAndDoesNotInsert() throws Exception {
        for (String parent : new String[]{UUID.randomUUID().toString(), "invalid"}) {
            JsonNode failure = json(mvc.perform(post("/api/catalogs/HttpHierarchy").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\":\"Child\",\"parent\":\"" + parent + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn());
            assertThat(failure.path("field").asText()).isEqualTo("parent");
            assertThat(failure.path("fieldErrors").path("parent").isArray()).isTrue();
            JsonNode preview = json(mvc.perform(post("/api/catalogs/HttpHierarchy/validate").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"parent\":\"" + parent + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn());
            assertThat(preview.path("valid").asBoolean()).isFalse();
            assertThat(preview.path("fieldErrors").path("parent").isArray()).isTrue();
        }
        long count = stack.jdbi().withHandle(h -> h.createQuery("SELECT COUNT(*) FROM catalog_http_hierarchy")
                .mapTo(Long.class).one());
        assertThat(count).isZero();
    }

    @Test void aCycleOnUpdateIs400AndLeavesTheTreeIntact() throws Exception {
        Node root = save(true, null);
        Node child = save(true, root.getId());
        JsonNode failure = json(mvc.perform(put("/api/catalogs/HttpHierarchy/{id}", root.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent\":\"" + child.getId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn());
        assertThat(failure.path("fieldErrors").path("parent").path(0).asText()).contains("cycle");
        assertThat(stack.store().find(Node.class, root.getId()).getParent()).isNull();
        assertThat(stack.store().find(Node.class, child.getId()).getParent()).isEqualTo(root.getId());
    }

    @Test void anItemParentIs400AndThePreviewReportsTheSameField() throws Exception {
        Node item = save(false, null);
        String body = "{\"parent\":\"" + item.getId() + "\"}";
        JsonNode failure = json(mvc.perform(post("/api/catalogs/HttpHierarchy").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn());
        assertThat(failure.path("fieldErrors").path("parent").path(0).asText()).isEqualTo("Parent must be a folder");
        JsonNode preview = json(mvc.perform(post("/api/catalogs/HttpHierarchy/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(preview.path("valid").asBoolean()).isFalse();
        assertThat(preview.path("fieldErrors").path("parent").path(0).asText()).isEqualTo("Parent must be a folder");
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private Node save(boolean folder, UUID parent) {
        Node node = new Node();
        node.setFolder(folder);
        node.setParent(parent);
        return stack.store().inTransaction(() -> stack.store().save(node));
    }
}
